package com.deepon.smartdocs.websocket.config;

import com.deepon.smartdocs.security.OriginMatcher;
import com.deepon.smartdocs.websocket.service.WsTicketService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * design doc D7, section 5.2: origin check, then single-use ticket
 * redemption, both before the upgrade completes. Rejecting here — setting
 * the status and returning {@code false} — means the browser sees a plain
 * HTTP error, never an accepted-then-closed socket the browser reports as an
 * opaque network error (edge case 1).
 */
@Component
public class WsHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WsHandshakeInterceptor.class);

    public static final String USER_ID_ATTR = "userId";
    public static final String SESSION_ID_ATTR = "sessionId";
    public static final String CONNECTED_AT_ATTR = "connectedAt";

    private final WsTicketService wsTicketService;
    private final Clock clock;
    private final List<String> allowedOrigins;
    private final boolean allowMissingOrigin;

    public WsHandshakeInterceptor(WsTicketService wsTicketService, Clock clock,
                                   @Value("${smartdocs.websocket.allowed-origins}") String allowedOrigins,
                                   @Value("${smartdocs.websocket.allow-missing-origin:false}") boolean allowMissingOrigin) {
        this.wsTicketService = wsTicketService;
        this.clock = clock;
        this.allowedOrigins = List.of(allowedOrigins.split(","));
        this.allowMissingOrigin = allowMissingOrigin;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return false;
        }
        HttpServletRequest httpRequest = servletRequest.getServletRequest();

        if (!originAllowed(httpRequest)) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        String ticket = extractTicket(request);
        // Never log the raw ticket value (design doc section 5.2).
        Optional<UUID> userId = wsTicketService.redeem(ticket);
        if (userId.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        attributes.put(USER_ID_ATTR, userId.get());
        attributes.put(SESSION_ID_ATTR, UUID.randomUUID());
        attributes.put(CONNECTED_AT_ATTR, clock.instant());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {
        if (exception != null) {
            log.warn("WebSocket handshake completed with an exception.", exception);
        }
    }

    private boolean originAllowed(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            // Edge case 4: non-browser clients sending no Origin are
            // rejected in production, allowed only when explicitly opted
            // into for local dev-tool testing.
            return allowMissingOrigin;
        }
        return allowedOrigins.stream().anyMatch(allowed -> OriginMatcher.matches(allowed.trim(), origin));
    }

    private String extractTicket(ServerHttpRequest request) {
        return UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("ticket");
    }
}
