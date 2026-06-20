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
 * AES-256-GCM 加密助手，用于敏感持久化值（当前为 DeepSeek / 服务商 API 密钥）。
 * 选择 GCM 是因为它是一种 AEAD 模式，能够在一次操作中同时提供机密性与完整性，
 * 无需叠加额外的 MAC。
 *
 * 与 ApiKeyService 等服务协作，这些服务在写入 / 读取时会通过
 * {@link #isEncryptionEnabled()} 进行门控，保证 ENCRYPTION_KEY 缺失或格式错误时不会阻塞启动。
 *
 * 向后兼容：当 ENCRYPTION_KEY 缺失时，加密被禁用，调用方回退到明文存储，
 * 允许遗留数据与新数据共存，直至密钥被正确配置。
 */
@Component
public class EncryptionUtil {

    private static final Logger log = LoggerFactory.getLogger(EncryptionUtil.class);

    // AES-GCM 不带 padding：GCM 是基于流的 AEAD 模式，不需要块填充，
    // 从而避免 PKCS#5/PKCS#7 填充带来的陷阱（padding-oracle 攻击、密文膨胀）。
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    // 96 位（12 字节）的 IV 是 NIST 为 GCM 推荐的尺寸：性能最佳
    // （无需额外哈希），并能最小化多次加密间的 IV 碰撞概率。
    private static final int GCM_IV_LENGTH = 12; // 推荐 GCM 使用 96 位 IV
    // 128 位认证标签：GCM 所支持的最强强度，与机密性一起
    // 提供强完整性 / 真实性保障。
    private static final int GCM_TAG_LENGTH = 128; // 128 位认证标签
    // 32 字节（256 位）密钥与 AES-256 强度匹配；启动期校验长度，
    // 让配置错误的密钥快速失败而不是悄悄削弱加密强度。
    private static final int KEY_LENGTH = 32; // 256 位用于 AES-256

    // 在 init() 中从 ENCRYPTION_KEY 懒加载构建；仅存在于内存（永不记录日志 / 持久化），
    // 因为一旦泄漏整个加密机制就形同虚设。
    private SecretKeySpec secretKey;
    // 加密 IV 生成必须使用 SecureRandom（而不是 java.util.Random）；
    // 跨调用复用，因为实例化开销大且只需种子化一次即可。
    private final SecureRandom secureRandom = new SecureRandom();
    // 标记 init() 是否成功加载了有效密钥，调用方（如 ApiKeyService）
    // 在向后兼容模式下可以走明文存储分支。
    private boolean encryptionEnabled = false;

    @PostConstruct
    /**
     * 在 Spring 上下文完成该 bean 的实例化后，从环境变量 ENCRYPTION_KEY 加载并校验 AES 密钥。
     * 副作用：构建 SecretKeySpec；成功时将 encryptionEnabled 置为 true；
     * 失败时输出警告（绝不抛错），以保证应用仍能以明文兼容模式启动。
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
            // 解码 Base64 编码的密钥
            byte[] keyBytes = Base64.getDecoder().decode(encryptionKey);

            // 校验密钥长度 —— AES-256 必须为 32 字节
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
     * 返回加密功能是否已正确配置并启用。
     * 由服务（例如 ApiKeyService 在持久化用户提供的 API key 之前）调用，
     * 以决定走 encrypt()/decrypt() 还是明文存储。
     *
     * @return 当 init() 成功加载了有效的 256 位 Base64 密钥时返回 true，否则 false
     */
    public boolean isEncryptionEnabled() {
        return encryptionEnabled;
    }

    /**
     * 使用 AES-256-GCM 加密明文。每次调用都会生成全新的 96 位 IV，
     * 以保证对相同明文两次加密得到不同密文（语义安全）。
     *
     * 契约：plaintext 必须非 null 且非空白；调用方需先自行检查 isEncryptionEnabled()。
     * 输出格式：Base64(IV || ciphertextWithTag)，便于通过 decrypt() 完整往返。
     *
     * @param plaintext 要加密的文本（例如用户提供的 DeepSeek API key）
     * @return Base64 编码的密文（IV 前置），可安全存入数据库或文本字段
     * @throws RuntimeException 加密失败时
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new RuntimeException("Cannot encrypt null or blank string");
        }

        try {
            // 生成随机 IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            // 初始化 cipher
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            // 加密
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // 将 IV 前置拼接到密文
            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            byteBuffer.put(iv);
            byteBuffer.put(ciphertext);

            return Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed: " + e.getMessage(), e);
        }
    }

    /**
     * 使用 AES-256-GCM 解密密文。流程为提取前置 IV，并以解密模式运行 AES-256-GCM，
     * 是 encrypt() 的逆过程。GCM 认证标签会在 doFinal() 阶段进行校验；
     * 任何篡改、截断或密钥错误都会触发 AEADBadTagException，
     * 在此处被包装为 RuntimeException。
     *
     * 契约：ciphertext 必须非 null，且为非空白的、本类产生的 Base64 串；
     * 在兼容模式下运行时应由调用方通过 isEncryptionEnabled() 进行门控。
     *
     * @param ciphertext encrypt() 返回的 Base64 编码密文（IV 前置）
     * @return 解密后的明文
     * @throws RuntimeException 解密失败时
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            throw new RuntimeException("Cannot decrypt null or blank string");
        }

        try {
            byte[] decoded = Base64.getDecoder().decode(ciphertext);

            // 从开头提取 IV
            ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byteBuffer.get(iv);
            byte[] encryptedBytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(encryptedBytes);

            // 初始化 cipher
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

            // 解密
            byte[] plaintext = cipher.doFinal(encryptedBytes);

            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed: " + e.getMessage(), e);
        }
    }
}
