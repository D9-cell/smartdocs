package com.deepon.smartdocs.user.dto;

import com.deepon.smartdocs.user.entity.UserSession;

import java.time.Instant;
import java.util.UUID;

public record SessionResponse(UUID id, Instant createdAt, Instant lastSeenAt, String userAgent, boolean current) {

    public static SessionResponse from(UserSession session, UUID currentSessionId) {
        return new SessionResponse(session.getId(), session.getCreatedAt(), session.getLastSeenAt(),
                session.getUserAgent(), session.getId().equals(currentSessionId));
    }
}
