package com.ycy.travelagent.ratelimit;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内令牌桶。单机限流够用；多实例部署时应换成 Redis + Lua 或网关限流。
 * 容量 = 每分钟允许次数，按秒匀速补充。
 */
public class RateLimiter {

    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;

        private Bucket(double tokens) {
            this.tokens = tokens;
            this.lastRefillNanos = System.nanoTime();
        }
    }

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final double capacity;
    private final double permitsPerSecond;

    public RateLimiter(int permitsPerMinute) {
        this.capacity = Math.max(1, permitsPerMinute);
        this.permitsPerSecond = this.capacity / 60.0;
    }

    public boolean tryAcquire(String key) {
        if (buckets.size() > 10_000) {
            buckets.clear();
        }
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity));
        synchronized (bucket) {
            long now = System.nanoTime();
            double elapsedSeconds = (now - bucket.lastRefillNanos) / 1_000_000_000.0;
            bucket.tokens = Math.min(capacity, bucket.tokens + elapsedSeconds * permitsPerSecond);
            bucket.lastRefillNanos = now;
            if (bucket.tokens < 1.0) {
                return false;
            }
            bucket.tokens -= 1.0;
            return true;
        }
    }
}
