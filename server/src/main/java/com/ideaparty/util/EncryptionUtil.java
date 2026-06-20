package com.ideaparty.util;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption helper for sensitive persisted values (currently DeepSeek / provider
 * API keys). GCM was chosen because it is an AEAD mode that gives confidentiality AND integrity
 * in one pass, removing the need to layer a separate MAC.
 *
 * Collaborates with services such as ApiKeyService, which gate writes/reads on
 * {@link #isEncryptionEnabled()} so that missing/malformed ENCRYPTION_KEY never blocks startup.
 *
 * Backward-compatibility: when ENCRYPTION_KEY is absent, encryption is disabled and callers
 * fall back to plain text storage, allowing legacy data to coexist until a key is provisioned.
 */
@Component
public class EncryptionUtil {

    private static final Logger log = LoggerFactory.getLogger(EncryptionUtil.class);

    // AES-GCM with no padding: GCM is a stream-based AEAD mode and does not require block padding,
    // avoiding the pitfalls of PKCS#5/PKCS#7 padding (padding-oracle attacks, ciphertext expansion).
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    // 96-bit (12-byte) IV is the NIST-recommended size for GCM: it maximizes performance
    // (no extra hashing) and minimizes collision probability across encryptions.
    private static final int GCM_IV_LENGTH = 12; // 96 bits recommended for GCM
    // 128-bit auth tag: the maximum strength supported by GCM, chosen to guarantee
    // strong integrity/authenticity guarantees alongside confidentiality.
    private static final int GCM_TAG_LENGTH = 128; // 128 bits authentication tag
    // 32-byte (256-bit) key matches AES-256 strength; length is validated at startup
    // so a misconfigured key fails fast instead of silently weakening crypto.
    private static final int KEY_LENGTH = 32; // 256 bits for AES-256

    // Lazily built from ENCRYPTION_KEY in init(); held in memory only (never logged/persisted)
    // because leaking it would defeat the entire purpose of encryption.
    private SecretKeySpec secretKey;
    // SecureRandom (not java.util.Random) is required for cryptographic IV generation;
    // reused across calls because instantiation is expensive and seeding it once is sufficient.
    private final SecureRandom secureRandom = new SecureRandom();
    // Tracks whether init() successfully loaded a valid key so callers (e.g. ApiKeyService)
    // can branch to plaintext storage when running in backward-compatibility mode.
    private boolean encryptionEnabled = false;

    @PostConstruct
    /**
     * Loads and validates the AES key from the ENCRYPTION_KEY environment variable once
     * the Spring context has instantiated this bean. Side effects: builds the SecretKeySpec,
     * flips encryptionEnabled to true on success, and emits a warning (never throws) on
     * failure so the application can still boot in plaintext compatibility mode.
     */
    public void init() {
        String encryptionKey = System.getenv("ENCRYPTION_KEY");
        if (encryptionKey == null || encryptionKey.isBlank()) {
            log.warn("ENCRYPTION_KEY environment variable is not set. " +
                "API key encryption is disabled. To enable, set a Base64-encoded 32-byte key: " +
                "openssl rand -base64 32. System will operate in backward compatibility mode.");
            this.encryptionEnabled = false;
            return;
        }

        try {
            // Decode the Base64-encoded key
            byte[] keyBytes = Base64.getDecoder().decode(encryptionKey);

            // Validate key length - must be 32 bytes for AES-256
            if (keyBytes.length != KEY_LENGTH) {
                log.warn("ENCRYPTION_KEY must be 32 bytes (256 bits). Current length: {} bytes. " +
                    "API key encryption is disabled. Generate a valid key with: openssl rand -base64 32. " +
                    "System will operate in backward compatibility mode.", keyBytes.length);
                this.encryptionEnabled = false;
                return;
            }

            this.secretKey = new SecretKeySpec(keyBytes, "AES");
            this.encryptionEnabled = true;
            log.info("EncryptionUtil initialized successfully with AES-256-GCM");
        } catch (IllegalArgumentException e) {
            log.warn("ENCRYPTION_KEY is not valid Base64. API key encryption is disabled. " +
                "Generate a valid key with: openssl rand -base64 32. System will operate in backward compatibility mode.", e);
            this.encryptionEnabled = false;
        }
    }

    /**
     * Returns whether encryption is properly configured and enabled.
     * Called by services (e.g. ApiKeyService before persisting user-provided API keys)
     * to decide whether to route through encrypt()/decrypt() or store values as plain text.
     *
     * @return true when init() successfully loaded a valid 256-bit Base64 key, false otherwise
     */
    public boolean isEncryptionEnabled() {
        return encryptionEnabled;
    }

    /**
     * Encrypts plaintext using AES-256-GCM. Each call generates a fresh 96-bit IV so that
     * encrypting the same plaintext twice yields different ciphertexts (semantic security).
     *
     * Contract: plaintext must be non-null and non-blank; caller is responsible for first
     * checking isEncryptionEnabled(). Output layout: Base64(IV || ciphertextWithTag), making
     * the value self-contained for round-trip via decrypt().
     *
     * @param plaintext the text to encrypt (e.g. a user-supplied DeepSeek API key)
     * @return Base64-encoded ciphertext (IV prepended) safe for DB/text storage
     * @throws RuntimeException if encryption fails
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new RuntimeException("Cannot encrypt null or blank string");
        }

        try {
            // Generate random IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            // Encrypt
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Prepend IV to ciphertext
            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            byteBuffer.put(iv);
            byteBuffer.put(ciphertext);

            return Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed: " + e.getMessage(), e);
        }
    }

    /**
     * Decrypts ciphertext using AES-256-GCM. Reverses encrypt() by extracting the prepended IV
     * and running AES-256-GCM in decrypt mode. The GCM auth tag is verified during doFinal();
     * any tampering, truncation, or wrong key causes an AEADBadTagException wrapped here as a
     * RuntimeException.
     *
     * Contract: ciphertext must be non-null, non-blank Base64 produced by this class; caller
     * should gate on isEncryptionEnabled() when running in compatibility mode.
     *
     * @param ciphertext Base64-encoded ciphertext (IV prepended) returned by encrypt()
     * @return decrypted plaintext
     * @throws RuntimeException if decryption fails
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            throw new RuntimeException("Cannot decrypt null or blank string");
        }

        try {
            byte[] decoded = Base64.getDecoder().decode(ciphertext);

            // Extract IV from beginning
            ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byteBuffer.get(iv);
            byte[] encryptedBytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(encryptedBytes);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

            // Decrypt
            byte[] plaintext = cipher.doFinal(encryptedBytes);

            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed: " + e.getMessage(), e);
        }
    }
}
