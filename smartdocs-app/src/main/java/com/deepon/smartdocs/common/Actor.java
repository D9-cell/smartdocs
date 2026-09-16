package com.deepon.smartdocs.common;

import java.util.UUID;

/**
 * Who is making the current call. Passed as an explicit method parameter
 * into every service, never read from a thread-local: Stage 3 dispatches
 * agent work onto background threads, and a thread-local silently resolves
 * to {@code null} there (design doc section 6.3).
 */
public record Actor(String actorId, ActorType type, UUID userId) {

    public static final Actor SYSTEM = new Actor("system", ActorType.SYSTEM, null);

    public static Actor human(UUID userId) {
        return new Actor("user:" + userId, ActorType.HUMAN, userId);
    }
}
