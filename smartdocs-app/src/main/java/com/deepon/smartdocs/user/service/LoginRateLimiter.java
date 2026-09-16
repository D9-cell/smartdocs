package com.deepon.smartdocs.user.service;

import com.deepon.smartdocs.user.exception.RateLimitedException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-instance in-memory sliding-window counters, keyed by {@code ip_hash}.
 * Not backed by {@code login_attempt} — that table is an audit log, this is
 * the actual rate-limit state (design doc section 15: "Rate limiter is
 * per-instance in memory for IP counters | Shared counter store | Stage 9").
 * Login counts failures only; register counts every attempt — the design
 * doc states the login threshold in terms of "failures" and the register
 * one has no such qualifier.
 */
@Component
public class LoginRateLimiter {

    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(15);
    private static final int LOGIN_MAX_FAILURES_PER_IP = 20;
    private static final Duration REGISTER_WINDOW = Duration.ofHours(1);
    private static final int REGISTER_MAX_ATTEMPTS_PER_IP = 3;

    private final Clock clock;
    private final Map<String, Deque<Instant>> loginFailuresByIp = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> registerAttemptsByIp = new ConcurrentHashMap<>();

    public LoginRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** @throws RateLimitedException if this IP has already logged 20+ failures in the last 15 minutes. */
    public void checkLoginRate(String ipHash) {
        Deque<Instant> timestamps = loginFailuresByIp.computeIfAbsent(ipHash, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            prune(timestamps, LOGIN_WINDOW);
            if (timestamps.size() >= LOGIN_MAX_FAILURES_PER_IP) {
                throw rateLimited(timestamps, LOGIN_WINDOW);
            }
        }
    }

    public void recordLoginFailure(String ipHash) {
        Deque<Instant> timestamps = loginFailuresByIp.computeIfAbsent(ipHash, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            timestamps.addLast(clock.instant());
        }
    }

    /** @throws RateLimitedException if this IP has already made 3+ register attempts in the last hour. */
    public void checkAndRecordRegisterAttempt(String ipHash) {
        Deque<Instant> timestamps = registerAttemptsByIp.computeIfAbsent(ipHash, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            prune(timestamps, REGISTER_WINDOW);
            if (timestamps.size() >= REGISTER_MAX_ATTEMPTS_PER_IP) {
                throw rateLimited(timestamps, REGISTER_WINDOW);
            }
            timestamps.addLast(clock.instant());
        }
    }

    private void prune(Deque<Instant> timestamps, Duration window) {
        Instant cutoff = clock.instant().minus(window);
        while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
            timestamps.pollFirst();
        }
    }

    private RateLimitedException rateLimited(Deque<Instant> timestamps, Duration window) {
        Instant oldest = timestamps.peekFirst();
        long retryAfter = Duration.between(clock.instant(), oldest.plus(window)).getSeconds();
        return new RateLimitedException(Math.max(retryAfter, 1));
    }
}
