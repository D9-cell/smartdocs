package com.deepon.smartdocs.realtime;

import java.time.Clock;

/**
 * Classic token bucket, one instance per session (design doc section 4.1,
 * 8 "Resources" #31): refills continuously at {@code perSecond} tokens/sec
 * up to {@code burst}, so a session that sends nothing for a while can burst
 * back up to the cap rather than being punished for having been idle.
 * Not thread-safe by itself — callers serialize access (a session's own
 * frames are already handled one at a time per the container's per-session
 * delivery order).
 */
public class TokenBucket {

    private final double perSecond;
    private final double burst;
    private final Clock clock;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(double perSecond, double burst, Clock clock) {
        this.perSecond = perSecond;
        this.burst = burst;
        this.clock = clock;
        this.tokens = burst;
        this.lastRefillNanos = nowNanos();
    }

    /** @return true and consumes one token if available, false (no consumption) if the bucket is empty. */
    public boolean tryConsume() {
        refill();
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    private void refill() {
        long now = nowNanos();
        double elapsedSeconds = Math.max(0, now - lastRefillNanos) / 1_000_000_000.0;
        tokens = Math.min(burst, tokens + elapsedSeconds * perSecond);
        lastRefillNanos = now;
    }

    private long nowNanos() {
        // Clock gives millisecond resolution at best; that's plenty for a
        // rate limiter measuring whole-second windows, and keeping Clock
        // (rather than System.nanoTime) is what makes this testable with a
        // fixed/advanceable clock.
        return clock.millis() * 1_000_000L;
    }
}
