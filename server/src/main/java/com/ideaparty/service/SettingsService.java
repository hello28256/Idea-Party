package com.ideaparty.service;

import com.ideaparty.entity.User;
import com.ideaparty.repository.UserRepository;
import com.ideaparty.util.EncryptionUtil;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 用户级 AI 配置服务：管理每位用户自己的 API Key（DeepSeek 等 LLM 凭据）。
 * 之所以独立成 Service 而非塞进 UserService，是因为读 API Key 是 AI 调用热路径
 *（每次 chat 都要拿），单独事务边界 + 加密/降级逻辑放这里更清晰；
 * 与 SecurityContextHolder 配合解析当前用户，与 AIService/AuthService 共享底层 UserRepository。
 */
@Service
@RequiredArgsConstructor
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    private final UserRepository userRepository;
    // Optional 因为加密是功能开关：当主密钥环境变量缺失时，bean 不会被生成，我们回退到明文存储。
    private final Optional<EncryptionUtil> encryptionUtil;

    /**
     * 从 Spring Security 上下文取出当前登录用户的 ID（principal 即 userId 字符串）。
     * 仅供本 Service 内部使用 —— 调用方拿不到 principal 时必须显式失败而不是返回 null，
     * 防止下游误用「未认证用户」身份。
     */
    public String getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() != null) {
            return authentication.getPrincipal().toString();
        }
        throw new RuntimeException("User not authenticated");
    }

    /**
     * 便捷入口：拿「当前请求用户」的明文 API Key。
     * 调用方：AI 聊天热路径（每次 chat 触发），所以必须只走一次 DB + 一次解密，不要缓存。
     * 返回 null 表示用户没配自己的 Key —— 调用方应回退到系统级 Key。
     */
    public String getApiKey() {
        UUID userId = UUID.fromString(getCurrentUserId());
        return getApiKeyById(userId.toString());
    }

    /**
     * 按 userId 读 API Key，负责「密文→明文」透明解密 + 日志脱敏。
     * 调用方：AIService 在 chat 时调用；Admin 场景也可直接传 userId 查。
     * 副作用：会打 INFO/WARN 日志（含 keyPreview），仅前 4 字符用于排查。
     * 返回值：明文 Key；若用户没配则返回 null（不是空串）。
     */
    public String getApiKeyById(String userIdStr) {
        log.info("[AI Config] Getting API key for userId={}", userIdStr);
        String apiKey = userRepository.findById(UUID.fromString(userIdStr))
                .map(User::getApiKey)
                .orElse(null);

        // 当加密启用且有加密值时进行解密
        if (apiKey != null && encryptionUtil.isPresent() && encryptionUtil.get().isEncryptionEnabled()) {
            try {
                String decrypted = encryptionUtil.get().decrypt(apiKey);
                log.info("[AI Config] Using user API key (encrypted, decrypted) for userId={}, keyPreview={}***",
                    userIdStr, apiKey.length() > 8 ? apiKey.substring(0, 4) : "****");
                return decrypted;
            } catch (RuntimeException e) {
                log.warn("Failed to decrypt API key, returning as-is: {}", e.getMessage());
                return apiKey;
            }
        }

        if (apiKey != null && !apiKey.isBlank()) {
            log.info("[AI Config] Using user API key for userId={}, keyPreview={}***",
                userIdStr, apiKey.length() > 8 ? apiKey.substring(0, 4) : "****");
        }
        return apiKey;
    }

    /**
     * 保存当前用户的 API Key，按需加密后入库。
     * 入参：apiKey 可为 null（表示清空）；空串也允许。
     * 事务：@Transactional 确保 User 实体更新与可能的加密异常回滚一致。
     * 失败策略：加密异常时降级存明文 + 打 ERROR，保证用户至少能保存成功。
     */
    @Transactional
    public void setApiKey(String apiKey) {
        UUID userId = UUID.fromString(getCurrentUserId());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // 当加密启用时进行加密
        if (apiKey != null && encryptionUtil.isPresent() && encryptionUtil.get().isEncryptionEnabled()) {
            try {
                user.setApiKey(encryptionUtil.get().encrypt(apiKey));
            } catch (RuntimeException e) {
                log.error("Failed to encrypt API key, storing as plain text: {}", e.getMessage());
                user.setApiKey(apiKey);
            }
        } else {
            user.setApiKey(apiKey);
        }

        userRepository.save(user);
    }

    /**
     * 显式清空当前用户的 API Key（用户在前端「重置/删除 Key」时调用）。
     * 复用 setApiKey(null) 而不是直接 user.setApiKey(null)，确保走相同的加密/事务路径。
     */
    @Transactional
    public void clearApiKey() {
        setApiKey(null);
    }
}
