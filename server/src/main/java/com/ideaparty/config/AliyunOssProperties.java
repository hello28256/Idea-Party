package com.ideaparty.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云 OSS + STS 配置。
 *
 * 所有字段从环境变量读(由 application.yml 转 ${ALIYUN_*}),代码里不允许出现
 * Secret 字面量,确保 .env.production 在 gitignore 时不会泄露。
 *
 * 部署: cp .env.production.example .env.production,填 ALIYUN_* 系列变量。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "aliyun")
public class AliyunOssProperties {

    private Oss oss = new Oss();
    private Sts sts = new Sts();

    @Data
    public static class Oss {
        /** 桶名,带横杠 idea-party-uploads */
        private String bucket;
        /** Region ID,不带 endpoint 后缀,如 oss-cn-shenzhen */
        private String region;
        /** OSS endpoint,SDK 用。例 https://oss-cn-shenzhen.aliyuncs.com */
        private String endpoint;
        /** 桶默认访问域名,前端 <img src> 用。例 https://idea-party-uploads.oss-cn-shenzhen.aliyuncs.com */
        private String bucketDomain;
        /** 上传 key 前缀,如 uploads/ */
        private String keyPrefix = "uploads/";
    }

    @Data
    public static class Sts {
        /** RAM 用户的 AK ID(用于 AssumeRole) */
        private String accessKeyId;
        /** RAM 用户的 AK Secret */
        private String accessKeySecret;
        /** RAM 角色 ARN,形如 acs:ram::UID:role/idea-party-uploader */
        private String roleArn;
        /** STS Session 名称,便于审计 */
        private String roleSessionName = "ideaparty-upload";
        /** 临时凭证有效期(秒),范围 [900, 3600] */
        private Integer durationSeconds = 3600;
    }
}
