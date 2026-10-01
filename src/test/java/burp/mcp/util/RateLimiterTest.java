package burp.mcp.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    @Test
    void zeroLimit_shouldDisableRateLimiting() {
        RateLimiter rl = new RateLimiter(0);
        assertThat(rl.tryConsume("10.0.0.1")).isZero();
        assertThat(rl.tryConsume("10.0.0.1")).isZero();
        assertThat(rl.tryConsume("10.0.0.1")).isZero();
    }

    @Test
    void negativeLimit_shouldDisableRateLimiting() {
        RateLimiter rl = new RateLimiter(-5);
        assertThat(rl.tryConsume("10.0.0.2")).isZero();
    }

    @Test
    void positiveLimit_shouldAllowFirstRequest() {
        RateLimiter rl = new RateLimiter(600);
        assertThat(rl.tryConsume("10.0.0.3")).isZero();
    }

    @Test
    void exhaustedBucket_shouldReturnWaitTime() {
        // limit 10/min -> burst of 1: first consumes the only token
        RateLimiter rl = new RateLimiter(10);
        assertThat(rl.tryConsume("10.0.0.4")).isZero();
        assertThat(rl.tryConsume("10.0.0.4")).isGreaterThan(0);
    }

    @Test
    void updateRateLimit_toZero_shouldDisable() {
        RateLimiter rl = new RateLimiter(10);
        assertThat(rl.tryConsume("10.0.0.5")).isZero();
        assertThat(rl.tryConsume("10.0.0.5")).isGreaterThan(0);
        rl.updateRateLimit(0);
        assertThat(rl.tryConsume("10.0.0.5")).isZero();
    }

    @Test
    void updateRateLimit_sameRate_shouldKeepBuckets() {
        RateLimiter rl = new RateLimiter(10);
        assertThat(rl.tryConsume("10.0.0.6")).isZero();
        rl.updateRateLimit(10);
        // Bucket state preserved: still exhausted
        assertThat(rl.tryConsume("10.0.0.6")).isGreaterThan(0);
    }
}
