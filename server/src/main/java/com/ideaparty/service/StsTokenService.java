package com.ideaparty.service;

import com.ideaparty.config.TencentCosProperties;
import com.tencentcloudapi.cam.v20190116.CamClient;
import com.tencentcloudapi.cam.v20190116.models.AssumeRoleRequest;
import com.tencentcloudapi.cam.v20190116.models.AssumeRoleResponse;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
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
 * 签发腾讯云 CAM STS 临时凭证,给前端浏览器直传 COS 用。
 *
 * 凭证缓存:AssumeRole 每次调用都要花钱(虽然很便宜),且有 QPS 限制。
 * 简单做法是拿到凭证后缓存,Expiration - 5min 内主动续。
 *
 * 安全:SecretKey 不进日志(DEBUG 级别只打 ID 前 4 位 + 过期时间)。
 * 缓存里的 Secret 不写磁盘、Spring 也不序列化它,只在内存里活到过期。
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
        // 凭证快过期或没缓存,加锁避免并发续期
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
            // CAM API endpoint 是固定的,与 COS 桶无关;但 endpoint 域名要
            // 用 cam.tencentcloudapi.com, region 用 ap-seoul (跟 COS 同地域)
            Credential cred = new Credential(cfg.getSecretId(), cfg.getSecretKey());
            HttpProfile httpProfile = new HttpProfile();
            httpProfile.setEndpoint("cam.tencentcloudapi.com");
            ClientProfile clientProfile = new ClientProfile();
            clientProfile.setHttpProfile(httpProfile);
            CamClient client = new CamClient(cred, props.getCos().getRegion(), clientProfile);

            AssumeRoleRequest request = new AssumeRoleRequest();
            request.setRoleArn(cfg.getRoleArn());
            request.setRoleSessionName(cfg.getRoleSessionName());
            // DurationSeconds 范围 [900, 7200],腾讯云用秒
            request.setDurationSeconds((long) Math.min(7200, Math.max(900, cfg.getDurationSeconds())));

            AssumeRoleResponse response = client.AssumeRole(request);
            AssumeRoleResponse.Credentials c = response.getCredentials();

            // 腾讯云 STS 返回的 Expiration 形如 "2026-07-03T15:00:00Z"
            long expireAt = parseExpiryMillis(c.getExpiration());
            CachedCredentials out = new CachedCredentials();
            out.setAccessKeyId(c.getTmpSecretId());
            out.setAccessKeySecret(c.getTmpSecretKey());
            out.setSecurityToken(c.getToken());
            out.setExpireAtMillis(expireAt);
            return out;
        } catch (TencentCloudSDKException e) {
            // 错误信息可能含 AK 残留,只打错误码
            log.error("[DEBUG] CAM AssumeRole 失败: code={}, requestId={}",
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
