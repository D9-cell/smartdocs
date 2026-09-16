package com.deepon.smartdocs.websocket.service;

import com.deepon.smartdocs.user.exception.RateLimitedException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-instance in-memory sliding window, same shape as {@code LoginRateLimiter}
 * (design doc section 6.1: 10 tickets per minute per user). Keyed by user id
 * rather than IP hash — a ticket is bound to an authenticated user, so the
 * meaningful abuse unit here is the account, not the caller's address.
 */
@Component
public class WsTicketRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int MAX_PER_WINDOW = 10;

    private final Clock clock;
    private final Map<UUID, Deque<Instant>> issuedByUser = new ConcurrentHashMap<>();

    public WsTicketRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** @throws RateLimitedException if this user has already issued 10+ tickets in the last minute. */
    public void checkAndRecord(UUID userId) {
        Deque<Instant> timestamps = issuedByUser.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            Instant cutoff = clock.instant().minus(WINDOW);
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= MAX_PER_WINDOW) {
                Instant oldest = timestamps.peekFirst();
                long retryAfter = Duration.between(clock.instant(), oldest.plus(WINDOW)).getSeconds();
                throw new RateLimitedException(Math.max(retryAfter, 1));
            }
            timestamps.addLast(clock.instant());
        }
    }
}
