package com.deepon.smartdocs.websocket.service.impl;

import com.deepon.smartdocs.common.IdGenerator;
import com.deepon.smartdocs.common.Sha256;
import com.deepon.smartdocs.websocket.entity.WsTicket;
import com.deepon.smartdocs.websocket.repository.WsTicketRepository;
import com.deepon.smartdocs.websocket.service.WsTicketRateLimiter;
import com.deepon.smartdocs.websocket.service.WsTicketService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Token generation mirrors {@code SessionServiceImpl} exactly: 256 random
 * bits, base64url encoded, only the SHA-256 hash stored. The raw token is
 * never logged and never persisted.
 */
@Service
public class WsTicketServiceImpl implements WsTicketService {

    private static final int TOKEN_BYTES = 32; // 256 bits
    private static final int MAX_CLIENT_IP_LENGTH = 45;
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final WsTicketRepository wsTicketRepository;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final WsTicketRateLimiter rateLimiter;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    public WsTicketServiceImpl(WsTicketRepository wsTicketRepository, IdGenerator idGenerator, Clock clock,
                                WsTicketRateLimiter rateLimiter,
                                @Value("${smartdocs.websocket.ticket-ttl-seconds:30}") long ttlSeconds) {
        this.wsTicketRepository = wsTicketRepository;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.rateLimiter = rateLimiter;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    @Override
    @Transactional
    public Issued issue(UUID userId, String clientIp) {
        rateLimiter.checkAndRecord(userId);

        String rawToken = generateRawToken();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        WsTicket ticket = new WsTicket(idGenerator.newId(), Sha256.hex(rawToken), userId, now, expiresAt, truncate(clientIp));
        wsTicketRepository.save(ticket);
        return new Issued(rawToken, expiresAt);
    }

    @Override
    @Transactional
    public Optional<UUID> redeem(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String tokenHash = Sha256.hex(rawToken);
        int rows = wsTicketRepository.redeem(tokenHash, clock.instant());
        if (rows == 0) {
            return Optional.empty();
        }
        return wsTicketRepository.findByTokenHash(tokenHash).map(WsTicket::getUserId);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return TOKEN_ENCODER.encodeToString(bytes);
    }

    private String truncate(String clientIp) {
        if (clientIp == null) {
            return null;
        }
        return clientIp.length() > MAX_CLIENT_IP_LENGTH ? clientIp.substring(0, MAX_CLIENT_IP_LENGTH) : clientIp;
    }
}
