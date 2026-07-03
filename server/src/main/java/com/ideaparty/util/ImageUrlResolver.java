package com.ideaparty.util;

import com.ideaparty.config.TencentCosProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 把 DB 里的图片字段(相对 key / 已 COS URL / null)统一归一成浏览器可直接 &lt;img src&gt; 的完整 URL。
 *
 * 规则:
 *   - null / blank → 原样返回(null 让 Jackson 序列化时省略或前端用 placeholder)
 *   - http:// or https:// → 原样返回(已是直链,避免重复拼)
 *   - 相对 key "uploads/avatars/xxx.jpg" → 拼 COS bucketDomain
 *   - 绝对 key "/uploads/avatars/xxx.jpg" → 去前导 / 再拼 COS
 *
 * 关键收益:DB 内部继续存相对 key,迁桶只改 TENCENT_COS_BUCKET_DOMAIN env,
 * 不用再写一次 SQL;前端永远只看到绝对 URL,不用做任何拼接。
 *
 * PR3: 阿里云 OSS → 腾讯云 COS,所有 getOss() 改 getCos()。
 */
@Component
@RequiredArgsConstructor
public class ImageUrlResolver {
    private final TencentCosProperties props;

    public String resolve(String keyOrUrl) {
        if (keyOrUrl == null || keyOrUrl.isBlank()) return keyOrUrl;
        if (keyOrUrl.startsWith("http://") || keyOrUrl.startsWith("https://")) return keyOrUrl;

        String bucketDomain = props.getCos().getBucketDomain();
        String keyPrefix = props.getCos().getKeyPrefix(); // "uploads/"

        String path = keyOrUrl.startsWith("/") ? keyOrUrl.substring(1) : keyOrUrl;
        // path 已含 keyPrefix 则不重复拼
        if (path.startsWith(keyPrefix)) {
            return bucketDomain + "/" + path;
        }
        return bucketDomain + "/" + keyPrefix + path;
    }
}