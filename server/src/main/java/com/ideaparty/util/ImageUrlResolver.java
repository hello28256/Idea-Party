package com.ideaparty.util;

import com.ideaparty.config.AliyunOssProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 把 DB 里的图片字段(相对 key / 已 OSS URL / null)统一归一成浏览器可直接 &lt;img src&gt; 的完整 URL。
 *
 * 规则:
 *   - null / blank → 原样返回(null 让 Jackson 序列化时省略或前端用 placeholder)
 *   - http:// or https:// → 原样返回(已是直链,避免重复拼)
 *   - 相对 key "uploads/avatars/xxx.jpg" → 拼 OSS bucketDomain
 *   - 绝对 key "/uploads/avatars/xxx.jpg" → 去前导 / 再拼 OSS
 *
 * 关键收益:DB 内部继续存相对 key,迁桶只改 ALIYUN_OSS_BUCKET_DOMAIN env,
 * 不用再写一次 SQL;前端永远只看到绝对 URL,不用做任何拼接。
 */
@Component
@RequiredArgsConstructor
public class ImageUrlResolver {
    private final AliyunOssProperties props;

    public String resolve(String keyOrUrl) {
        if (keyOrUrl == null || keyOrUrl.isBlank()) return keyOrUrl;
        if (keyOrUrl.startsWith("http://") || keyOrUrl.startsWith("https://")) return keyOrUrl;

        String bucketDomain = props.getOss().getBucketDomain();
        String keyPrefix = props.getOss().getKeyPrefix(); // "uploads/"

        String path = keyOrUrl.startsWith("/") ? keyOrUrl.substring(1) : keyOrUrl;
        // path 已含 keyPrefix 则不重复拼
        if (path.startsWith(keyPrefix)) {
            return bucketDomain + "/" + path;
        }
        return bucketDomain + "/" + keyPrefix + path;
    }
}