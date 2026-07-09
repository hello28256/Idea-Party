package com.ideaparty.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 腾讯云 COS + CAM 配置。
 *
 * 所有字段从环境变量读(由 application.yml 转 ${TENCENT_COS_*}),代码里不允许出现
 * Secret 字面量,确保 .env.production 在 gitignore 时不会泄露。
 *
 * 部署: 在 .env.production 设置 TENCENT_COS_SECRET_ID / TENCENT_COS_SECRET_KEY /
 *       TENCENT_COS_ROLE_ARN。
 *       - SECRET_ID/SECRET_KEY: CAM 子账号的永久 AK, 用于调 AssumeRole
 *       - ROLE_ARN: CAM 角色, 绑 QcloudCOSFullAccess 策略, 限定能访问的桶
 *
 * PR2 新增; PR3 已删 AliyunOssProperties.java, 唯一 OSS 配置源。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "tencent")
public class TencentCosProperties {

    private Cos cos = new Cos();
    private Cam cam = new Cam();

    @Data
    public static class Cos {
        /** 桶名带 APPID, idea-party-uploads-1361890600 */
        private String bucket;
        /** COS region, ap-guangzhou */
        private String region;
        /** COS endpoint (bucket 默认访问域名),SDK 用 */
        private String endpoint;
        /** 桶默认访问域名,前端 <img src> 用 */
        private String bucketDomain;
        /** 上传 key 前缀, 如 uploads/ */
        private String keyPrefix = "uploads/";
    }

    @Data
    public static class Cam {
        /** CAM 子账号 SecretId(永久 AK),用于调 AssumeRole */
        private String secretId;
        /** CAM 子账号 SecretKey */
        private String secretKey;
        /** CAM 角色 ARN,形如 qcs::cam::uin/10000xxx:roleName/jiaose */
        private String roleArn;
        /** STS Session 名称,便于审计 */
        private String roleSessionName = "ideaparty-cos-upload";
        /** 临时凭证有效期(秒),范围 [900, 7200] */
        private Integer durationSeconds = 3600;
    }
}
