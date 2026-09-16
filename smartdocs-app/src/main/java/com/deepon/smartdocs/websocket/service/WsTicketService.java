package com.deepon.smartdocs.websocket.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Single-use WebSocket handshake credential (design doc D7). Kept free of
 * {@code HttpServletRequest} types, same rule as every other service —
 * {@code WsTicketController} and {@code WsHandshakeInterceptor} own the HTTP
 * shape, this owns the rule.
 */
public interface WsTicketService {

    record Issued(String rawToken, Instant expiresAt) {
    }

    Issued issue(UUID userId, String clientIp);

    /** @return the ticket's owning user id, or empty when the token is missing, unknown, expired, or already used. */
    Optional<UUID> redeem(String rawToken);
}
