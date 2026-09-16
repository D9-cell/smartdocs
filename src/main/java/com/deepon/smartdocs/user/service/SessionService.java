package com.deepon.smartdocs.user.service;

import com.deepon.smartdocs.common.Actor;
import com.deepon.smartdocs.user.entity.UserSession;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Token generation, hashing, lookup, renewal, revocation. Owns no password
 * logic (design doc section 6.2 class responsibility table).
 */
public interface SessionService {

    String COOKIE_NAME = "sid";
    Duration IDLE_TIMEOUT = Duration.ofDays(7);
    Duration ABSOLUTE_TIMEOUT = Duration.ofDays(30);
    Duration RENEWAL_THRESHOLD = Duration.ofSeconds(60);
    int MAX_SESSIONS_PER_USER = 10;

    record Created(UUID sessionId, String rawToken, Instant absoluteExpiresAt) {
    }

    record Resolved(Actor actor, UUID sessionId) {
    }

    record Rotated(String rawToken, Instant absoluteExpiresAt) {
    }

    Created create(UUID userId, String userAgent, String ipHash);

    /**
     * Empty for a missing, unknown, tampered, revoked, or expired token, or
     * one belonging to a now-inactive user — the caller cannot and should
     * not distinguish which (design doc section 10.3). Performs sliding
     * renewal as a side effect when the session is valid and due for it.
     */
    Optional<Resolved> resolve(String rawToken);

    void revoke(UUID sessionId, String reason);

    /** @throws com.deepon.smartdocs.user.exception.SessionNotFoundException if {@code sessionId} doesn't exist or isn't owned by {@code userId}. */
    void revokeOwned(UUID sessionId, UUID userId);

    void revokeAllExcept(UUID userId, UUID exceptSessionId, String reason);

    /** Used on password change to defeat session fixation. */
    Rotated rotate(UUID sessionId);

    List<UserSession> listActive(UUID userId);
}
