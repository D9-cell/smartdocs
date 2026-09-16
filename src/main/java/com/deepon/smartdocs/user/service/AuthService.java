package com.deepon.smartdocs.user.service;

import com.deepon.smartdocs.common.Actor;
import com.deepon.smartdocs.user.entity.AppUser;
import com.deepon.smartdocs.user.service.SessionService.Rotated;

import java.time.Instant;
import java.util.UUID;

/** Register, login, logout, password-change orchestration. Never sees {@code HttpServletRequest} or cookies. */
public interface AuthService {

    record AuthResult(AppUser user, String rawToken, Instant absoluteExpiresAt) {
    }

    AuthResult register(String rawEmail, String rawPassword, String rawDisplayName, String userAgent, String ipAddress);

    /**
     * @param existingSessionId a currently-valid session presented alongside this login call, or
     *                          {@code null}. When present it is revoked with reason {@code RELOGIN}
     *                          before the new session is issued (design doc section 10.2).
     */
    AuthResult login(String rawEmail, String rawPassword, String userAgent, String ipAddress, UUID existingSessionId);

    /** Idempotent: revoking an already-revoked or unknown session id is a no-op. */
    void logout(UUID sessionId);

    /** Returns the rotated session's new raw token and expiry — the caller must re-set the cookie with it. */
    Rotated changePassword(Actor actor, UUID currentSessionId, String currentPassword, String newPassword);
}
