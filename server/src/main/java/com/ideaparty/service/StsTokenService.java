package com.ideaparty.service;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.auth.sts.AssumeRoleRequest;
import com.aliyuncs.auth.sts.AssumeRoleResponse;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.profile.DefaultProfile;
import com.ideaparty.config.AliyunOssProperties;
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
 * 签发阿里云 STS 临时凭证,给前端浏览器直传 OSS 用。
 *
 * 凭证缓存:AssumeRole 每次调用都要花钱(虽然很便宜),且有 QPS 限制。
 * 简单做法是拿到凭证后缓存,Expiration - 5min 内主动续。
 *
 * 安全:AccessKeySecret 不进日志(DEBUG 级别只打 ID 前 4 位 + 过期时间)。
 * 缓存里的 Secret 不写磁盘、Spring 也不序列化它,只在内存里活到过期。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StsTokenService {

    private final AliyunOssProperties props;

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
        AliyunOssProperties.Sts cfg = props.getSts();
        if (cfg.getAccessKeyId() == null || cfg.getAccessKeySecret() == null
                || cfg.getRoleArn() == null) {
            throw new IllegalStateException(
                    "ALIYUN_STS_* 未配置。检查 .env.production 里的 ALIYUN_STS_ACCESS_KEY_ID / "
                            + "ALIYUN_STS_ACCESS_KEY_SECRET / ALIYUN_STS_ROLE_ARN 是否设置");
        }
        // STS endpoint 是固定的,与 bucket 无关;但 region 要和 bucket 同地域
        // (因为 STS 走 VPC 域,这里用 cn-shenzhen)
        String region = "cn-shenzhen";
        DefaultProfile profile = DefaultProfile.getProfile(region, cfg.getAccessKeyId(), cfg.getAccessKeySecret());
        IAcsClient client = new DefaultAcsClient(profile);

        AssumeRoleRequest request = new AssumeRoleRequest();
        request.setSysRegionId(region);
        request.setRoleArn(cfg.getRoleArn());
        request.setRoleSessionName(cfg.getRoleSessionName());
        // DurationSeconds 范围 [900, 3600],越短越安全
        request.setDurationSeconds((long) Math.min(3600, Math.max(900, cfg.getDurationSeconds())));

        try {
            AssumeRoleResponse response = client.getAcsResponse(request);
            AssumeRoleResponse.Credentials c = response.getCredentials();
            long expireAt = parseExpiryMillis(c.getExpiration());
            CachedCredentials out = new CachedCredentials();
            out.setAccessKeyId(c.getAccessKeyId());
            out.setAccessKeySecret(c.getAccessKeySecret());
            out.setSecurityToken(c.getSecurityToken());
            out.setExpireAtMillis(expireAt);
            return out;
        } catch (ClientException e) {
            // 错误信息可能含 AK 残留,只打错误码
            log.error("[DEBUG] STS AssumeRole 失败: code={}, requestId={}",
                    e.getErrCode(), e.getRequestId());
            throw new RuntimeException("STS 签发失败: " + e.getErrCode(), e);
        }
    }

    /** STS 返回的 Expiration 形如 "2026-07-01T15:00:00Z" */
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
        private String accessKeyId;
        private String accessKeySecret;
        private String securityToken;
        private long expireAtMillis;
    }
}
