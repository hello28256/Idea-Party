package com.ideaparty.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Bucket4j 的内存级限流器配置。
 * 存在的原因：在不引入 Redis 的前提下，为每个客户端（IP / userId）提供独立的令牌桶，
 * 与 {@code RateLimitInterceptor} 配合，对高频接口（尤其是 AI 调用链路）做削峰保护。
 */
@Component
public class RateLimiterConfig {

    // 单客户端每秒允许通过的请求数；30 是经验值，可覆盖正常用户交互同时挡住脚本式抓取。
    private static final int REQUESTS_PER_SECOND = 30;
    // 单客户端每分钟允许回填的令牌数；与秒级限制叠加，避免突发流量在长窗口内被全部放行。
    private static final int REQUESTS_PER_MINUTE = 200;

    // 客户端 key -> 该客户端的令牌桶映射；使用 ConcurrentHashMap 保证多线程下 computeIfAbsent 的原子性，避免重复创建桶。
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * 为给定客户端构造一个全新的令牌桶。
     * 契约：入参 clientKey 仅用于在调用方做命名一致性校验，桶本身不消费 key；
     *       返回的桶使用 greedy refill，以 1 分钟为周期线性回填令牌，
     *       同时叠加瞬时秒级上限，保证短时突刺与长时均值都受限。
     */
    public Bucket createBucket(String clientKey) {
        Bandwidth limit = Bandwidth.classic(
            REQUESTS_PER_SECOND,
            Refill.greedy(REQUESTS_PER_MINUTE, Duration.ofMinutes(1))
        );
        return Bucket.builder().addLimit(limit).build();
    }

    /**
     * 解析（或懒创建）指定客户端的令牌桶。
     * 契约：相同 clientKey 在进程生命周期内复用同一桶实例，
     *       副作用是首次访问该 key 的线程会同步构造桶（开销可忽略），
     *       由 {@code RateLimitInterceptor} 在请求入口调用。
     */
    public Bucket resolveBucket(String clientKey) {
        return buckets.computeIfAbsent(clientKey, this::createBucket);
    }
}
