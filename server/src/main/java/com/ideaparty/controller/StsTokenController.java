package com.ideaparty.controller;

import com.ideaparty.config.TencentCosProperties;
import com.ideaparty.service.StsTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 给前端浏览器签发 STS 临时凭证,用于直传腾讯云 COS。
 *
 * GET /api/uploads/sts-token
 *   要求:登录用户
 *   返回:JSON { accessKeyId, accessKeySecret, securityToken, expiration, bucket, region, cosDomain, keyPrefix }
 *
 * PR2 切换: 阿里云 OSS → 腾讯云 COS,字段名从 ossDomain 改为 cosDomain (前端同步)。
 * 设计:每个登录用户拿到的凭证是同一个(共用 CAM 角色),不做按用户限流。
 */
@Slf4j
@RestController
@RequestMapping("/api/uploads")
@RequiredArgsConstructor
public class StsTokenController {

    private final StsTokenService stsTokenService;
    private final TencentCosProperties props;

    @GetMapping("/sts-token")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> getStsToken() {
        StsTokenService.CachedCredentials creds = stsTokenService.getCredentials();
        TencentCosProperties.Cos cos = props.getCos();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accessKeyId", creds.getAccessKeyId());
        out.put("accessKeySecret", creds.getAccessKeySecret());
        out.put("securityToken", creds.getSecurityToken());
        // ISO-8601,前端 Date.parse 能直接吃
        out.put("expiration", Instant.ofEpochSecond(creds.getExpireAtMillis() / 1000).toString());
        out.put("bucket", cos.getBucket());
        out.put("region", cos.getRegion());
        out.put("cosDomain", cos.getBucketDomain());
        out.put("keyPrefix", cos.getKeyPrefix());
        return out;
    }
}
