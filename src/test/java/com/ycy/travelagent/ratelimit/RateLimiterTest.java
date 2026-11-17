package com.ycy.travelagent.ratelimit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    @Test
    void allowsUpToCapacityThenRejects() {
        RateLimiter limiter = new RateLimiter(2);
        assertTrue(limiter.tryAcquire("u1"));
        assertTrue(limiter.tryAcquire("u1"));
        assertFalse(limiter.tryAcquire("u1"));
    }

    @Test
    void bucketsAreIsolatedPerKey() {
        RateLimiter limiter = new RateLimiter(1);
        assertTrue(limiter.tryAcquire("alice"));
        assertTrue(limiter.tryAcquire("bob"));
        assertFalse(limiter.tryAcquire("alice"));
    }
}
