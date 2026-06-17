package com.ideaparty.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimiterConfig {

    private static final int REQUESTS_PER_SECOND = 30;
    private static final int REQUESTS_PER_MINUTE = 200;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public Bucket createBucket(String clientKey) {
        Bandwidth limit = Bandwidth.classic(
            REQUESTS_PER_SECOND,
            Refill.greedy(REQUESTS_PER_MINUTE, Duration.ofMinutes(1))
        );
        return Bucket.builder().addLimit(limit).build();
    }

    public Bucket resolveBucket(String clientKey) {
        return buckets.computeIfAbsent(clientKey, this::createBucket);
    }
}
