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
 * Encryption utility for sensitive data using AES-256-GCM.
 * GCM mode provides both confidentiality and authenticity (authenticated encryption).
 *
 * When ENCRYPTION_KEY environment variable is not configured, encryption is disabled
 * and the system operates in backward compatibility mode (plain text storage).
 */
@Component
public class EncryptionUtil {

    private static final Logger log = LoggerFactory.getLogger(EncryptionUtil.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12; // 96 bits recommended for GCM
    private static final int GCM_TAG_LENGTH = 128; // 128 bits authentication tag
    private static final int KEY_LENGTH = 32; // 256 bits for AES-256

    private SecretKeySpec secretKey;
    private final SecureRandom secureRandom = new SecureRandom();
    private boolean encryptionEnabled = false;

    @PostConstruct
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
     */
    public boolean isEncryptionEnabled() {
        return encryptionEnabled;
    }

    /**
     * Encrypts plaintext using AES-256-GCM.
     *
     * @param plaintext the text to encrypt
     * @return Base64-encoded ciphertext (IV prepended)
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
     * Decrypts ciphertext using AES-256-GCM.
     *
     * @param ciphertext Base64-encoded ciphertext (IV prepended)
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
