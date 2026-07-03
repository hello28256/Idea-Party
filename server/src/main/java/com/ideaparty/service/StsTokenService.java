package com.ideaparty.service;

import com.ideaparty.config.TencentCosProperties;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.sts.v20180813.StsClient;
import com.tencentcloudapi.sts.v20180813.models.AssumeRoleRequest;
import com.tencentcloudapi.sts.v20180813.models.AssumeRoleResponse;
import com.tencentcloudapi.sts.v20180813.models.Credentials;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 签发腾讯云 STS 临时凭证,给前端浏览器直传 COS 用。
 *
 * PR2 改写: 用 tencentcloud-sdk-java STS SDK 调 AssumeRole API。
 * 注意: AssumeRole API 走 sts.tencentcloudapi.com (不是 cam.tencentcloudapi.com),
 * 正确包路径: com.tencentcloudapi.sts.v20180813.models.AssumeRoleRequest。
 *
 * 凭证缓存: STS 凭证 ExpiresAt - 5min 主动续,避免前端拿到快过期的凭证。
 *
 * 安全: SecretKey 不进日志(DEBUG 级别只打 ID 前 4 位 + 过期时间)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StsTokenService {

    private final TencentCosProperties props;

    /** 提前 5 分钟续期,避免前端拿到快过期的凭证 */
    private static final long RENEW_BEFORE_EXPIRY_MS = 5 * 60 * 1000;

    private volatile CachedCredentials cache;
    private final ReentrantLock renewLock = new ReentrantLock();

    /**
     * 获取当前可用的 STS 临时凭证(必要时自动续)。
     * 线程安全:多用户同时调只会触发一次 AssumeRole。
     */
    public CachedCredentials getCredentials() {
        CachedCredentials current = cache;
        long now = System.currentTimeMillis();
        if (current != null && current.getExpireAtMillis() - now > RENEW_BEFORE_EXPIRY_MS) {
            return current;
        }
        renewLock.lock();
        try {
            current = cache;
            if (current != null && current.getExpireAtMillis() - now > RENEW_BEFORE_EXPIRY_MS) {
                return current;
            }
            CachedCredentials fresh = assumeRole();
            cache = fresh;
            log.info("[DEBUG] STS 凭证已续期: akId={}***, expires={}",
                    mask(fresh.getAccessKeyId()),
                    formatExpiry(fresh.getExpireAtMillis()));
            return fresh;
        } finally {
            renewLock.unlock();
        }
    }

    private CachedCredentials assumeRole() {
        TencentCosProperties.Cam cfg = props.getCam();
        if (cfg.getSecretId() == null || cfg.getSecretKey() == null
                || cfg.getRoleArn() == null) {
            throw new IllegalStateException(
                    "TENCENT_COS_* 未配置。检查 .env.production 里的 TENCENT_COS_SECRET_ID / "
                            + "TENCENT_COS_SECRET_KEY / TENCENT_COS_ROLE_ARN 是否设置");
        }
        try {
            // STS AssumeRole API endpoint 是固定的 sts.tencentcloudapi.com
            // region 用 COS 桶同地域 (ap-seoul), 签名要 region 参数
            Credential cred = new Credential(cfg.getSecretId(), cfg.getSecretKey());
            HttpProfile httpProfile = new HttpProfile();
            httpProfile.setEndpoint("sts.tencentcloudapi.com");
            ClientProfile clientProfile = new ClientProfile();
            clientProfile.setHttpProfile(httpProfile);
            StsClient client = new StsClient(cred, props.getCos().getRegion(), clientProfile);

            AssumeRoleRequest request = new AssumeRoleRequest();
            request.setRoleArn(cfg.getRoleArn());
            request.setRoleSessionName(cfg.getRoleSessionName());
            // DurationSeconds 范围 [900, 7200],腾讯云用秒
            request.setDurationSeconds((long) Math.min(7200, Math.max(900, cfg.getDurationSeconds())));

            AssumeRoleResponse response = client.AssumeRole(request);
            Credentials c = response.getCredentials();

            // 腾讯云 STS 返回的 Expiration 形如 "2026-07-03T15:00:00Z", 在 response 上
            long expireAt = parseExpiryMillis(response.getExpiration());
            CachedCredentials out = new CachedCredentials();
            out.setAccessKeyId(c.getTmpSecretId());
            out.setAccessKeySecret(c.getTmpSecretKey());
            out.setSecurityToken(c.getToken());
            out.setExpireAtMillis(expireAt);
            return out;
        } catch (TencentCloudSDKException e) {
            log.error("[DEBUG] STS AssumeRole 失败: code={}, requestId={}",
                    e.getErrorCode(), e.getRequestId());
            throw new RuntimeException("STS 签发失败: " + e.getErrorCode(), e);
        }
    }

    /** STS 返回的 Expiration 形如 "2026-07-03T15:00:00Z" */
    private static long parseExpiryMillis(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    private static String formatExpiry(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /** 脱敏:只显示前 4 字符 */
    private static String mask(String s) {
        if (s == null || s.length() < 4) return "****";
        return s.substring(0, 4);
    }

    /** 缓存中的凭证,前端拿这个去 PutObject */
    @Data
    public static class CachedCredentials {
        /** 临时 SecretId(腾讯云 STS 字段名是 TmpSecretId) */
        private String accessKeyId;
        /** 临时 SecretKey(腾讯云 STS 字段名是 TmpSecretKey) */
        private String accessKeySecret;
        /** 临时 SecurityToken(腾讯云 STS 字段名是 Token) */
        private String securityToken;
        private long expireAtMillis;
    }
}
