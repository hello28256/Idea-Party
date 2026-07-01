package com.ideaparty.controller;

import com.ideaparty.config.AliyunOssProperties;
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
 * 给前端浏览器签发 STS 临时凭证,用于直传阿里云 OSS。
 *
 * GET /api/uploads/sts-token
 *   要求:登录用户
 *   返回:JSON { accessKeyId, accessKeySecret, securityToken, expiration, bucket, region, ossDomain, keyPrefix }
 *
 * 设计:每个登录用户拿到的凭证是同一个(共用 RAM 角色的权限),不做按用户限流。
 * 如果以后要限流,在 STS role session name 里嵌入 userId,RAM 策略里加 sts:RoleSessionName 条件。
 */
@Slf4j
@RestController
@RequestMapping("/api/uploads")
@RequiredArgsConstructor
public class StsTokenController {

    private final StsTokenService stsTokenService;
    private final AliyunOssProperties props;

    @GetMapping("/sts-token")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> getStsToken() {
        StsTokenService.CachedCredentials creds = stsTokenService.getCredentials();
        AliyunOssProperties.Oss oss = props.getOss();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accessKeyId", creds.getAccessKeyId());
        out.put("accessKeySecret", creds.getAccessKeySecret());
        out.put("securityToken", creds.getSecurityToken());
        // ISO-8601,前端 Date.parse 能直接吃
        out.put("expiration", Instant.ofEpochMilli(creds.getExpireAtMillis()).toString());
        out.put("bucket", oss.getBucket());
        out.put("region", oss.getRegion());
        out.put("ossDomain", oss.getBucketDomain());
        out.put("keyPrefix", oss.getKeyPrefix());
        return out;
    }
}
