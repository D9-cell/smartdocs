package com.deepon.smartdocs.user.service.impl;

import com.deepon.smartdocs.common.Actor;
import com.deepon.smartdocs.common.IdGenerator;
import com.deepon.smartdocs.common.Sha256;
import com.deepon.smartdocs.user.entity.AppUser;
import com.deepon.smartdocs.user.entity.UserSession;
import com.deepon.smartdocs.user.exception.SessionNotFoundException;
import com.deepon.smartdocs.user.repository.SessionRepository;
import com.deepon.smartdocs.user.repository.UserRepository;
import com.deepon.smartdocs.user.service.SessionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class SessionServiceImpl implements SessionService {

    private static final int TOKEN_BYTES = 32; // 256 bits
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public SessionServiceImpl(SessionRepository sessionRepository, UserRepository userRepository,
                               IdGenerator idGenerator, Clock clock) {
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Created create(UUID userId, String userAgent, String ipHash) {
        // Cap at MAX_SESSIONS_PER_USER active sessions; the oldest is
        // revoked on the session that would exceed it (design doc 10.3).
        if (sessionRepository.countByUserIdAndRevokedAtIsNull(userId) >= MAX_SESSIONS_PER_USER) {
            sessionRepository.findFirstByUserIdAndRevokedAtIsNullOrderByCreatedAtAsc(userId)
                    .ifPresent(oldest -> sessionRepository.revoke(oldest.getId(), clock.instant(), "SESSION_CAP_EXCEEDED"));
        }

        String rawToken = generateRawToken();
        Instant now = clock.instant();
        UUID sessionId = idGenerator.newId();
        UserSession session = new UserSession(sessionId, userId, Sha256.hex(rawToken), now,
                now.plus(IDLE_TIMEOUT), now.plus(ABSOLUTE_TIMEOUT), truncateUserAgent(userAgent), ipHash);
        sessionRepository.save(session);
        return new Created(sessionId, rawToken, session.getAbsoluteExpiresAt());
    }

    @Override
    @Transactional
    public Optional<Resolved> resolve(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }

        Optional<UserSession> found = sessionRepository.findByTokenHash(Sha256.hex(rawToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        UserSession session = found.get();
        Instant now = clock.instant();

        if (session.getRevokedAt() != null) {
            return Optional.empty();
        }
        if (!session.getIdleExpiresAt().isAfter(now)) {
            sessionRepository.revoke(session.getId(), now, "IDLE_EXPIRED");
            return Optional.empty();
        }
        if (!session.getAbsoluteExpiresAt().isAfter(now)) {
            sessionRepository.revoke(session.getId(), now, "ABSOLUTE_EXPIRED");
            return Optional.empty();
        }

        Optional<AppUser> user = userRepository.findByIdAndDeletedAtIsNull(session.getUserId());
        if (user.isEmpty() || !user.get().isActive()) {
            return Optional.empty();
        }

        if (Duration.between(session.getLastSeenAt(), now).compareTo(RENEWAL_THRESHOLD) >= 0) {
            sessionRepository.touch(session.getId(), now, now.plus(IDLE_TIMEOUT));
        }

        return Optional.of(new Resolved(Actor.human(session.getUserId()), session.getId()));
    }

    @Override
    @Transactional
    public void revoke(UUID sessionId, String reason) {
        sessionRepository.revoke(sessionId, clock.instant(), reason);
    }

    @Override
    @Transactional
    public void revokeOwned(UUID sessionId, UUID userId) {
        UserSession session = sessionRepository.findById(sessionId)
                .filter(s -> s.getUserId().equals(userId))
                .orElseThrow(SessionNotFoundException::new);
        sessionRepository.revoke(session.getId(), clock.instant(), "USER_REVOKED");
    }

    @Override
    @Transactional
    public void revokeAllExcept(UUID userId, UUID exceptSessionId, String reason) {
        sessionRepository.revokeAllForUserExcept(userId, exceptSessionId, clock.instant(), reason);
    }

    @Override
    @Transactional
    public Rotated rotate(UUID sessionId) {
        UserSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalStateException("Cannot rotate a session that doesn't exist: " + sessionId));
        String rawToken = generateRawToken();
        sessionRepository.updateTokenHash(sessionId, Sha256.hex(rawToken));
        return new Rotated(rawToken, session.getAbsoluteExpiresAt());
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserSession> listActive(UUID userId) {
        Instant now = clock.instant();
        return sessionRepository.findByUserIdAndRevokedAtIsNullOrderByLastSeenAtDesc(userId).stream()
                .filter(s -> s.isValidAt(now))
                .toList();
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return TOKEN_ENCODER.encodeToString(bytes);
    }

    private String truncateUserAgent(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        return userAgent.length() > 255 ? userAgent.substring(0, 255) : userAgent;
    }
}
