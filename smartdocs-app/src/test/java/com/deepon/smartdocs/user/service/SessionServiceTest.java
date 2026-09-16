package com.deepon.smartdocs.user.service;

import com.deepon.smartdocs.common.Actor;
import com.deepon.smartdocs.common.IdGenerator;
import com.deepon.smartdocs.support.AbstractPostgresTest;
import com.deepon.smartdocs.support.TestUsers;
import com.deepon.smartdocs.user.entity.UserSession;
import com.deepon.smartdocs.user.repository.SessionRepository;
import com.deepon.smartdocs.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct coverage of {@link SessionService}'s lifecycle — token generation,
 * hashing, lookup, renewal, revocation — against a real database (design doc
 * section 12's >90% line-coverage target names this class explicitly).
 */
@SpringBootTest
class SessionServiceTest extends AbstractPostgresTest {

    @Autowired
    private SessionService sessionService;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private IdGenerator idGenerator;

    @Autowired
    private Clock clock;

    private UUID userId;

    @BeforeEach
    void createTestUser() {
        Actor actor = TestUsers.createActor(userRepository, idGenerator, clock);
        userId = actor.userId();
    }

    @Test
    void createThenResolveReturnsTheSameActor() {
        SessionService.Created created = sessionService.create(userId, "TestAgent/1.0", "iphash");

        Optional<SessionService.Resolved> resolved = sessionService.resolve(created.rawToken());

        assertThat(resolved).isPresent();
        assertThat(resolved.get().actor().userId()).isEqualTo(userId);
        assertThat(resolved.get().sessionId()).isEqualTo(created.sessionId());
    }

    @Test
    void theRawTokenNeverAppearsInTheDatabase() {
        SessionService.Created created = sessionService.create(userId, "TestAgent/1.0", "iphash");

        UserSession row = sessionRepository.findById(created.sessionId()).orElseThrow();
        assertThat(row.getTokenHash()).isNotEqualTo(created.rawToken());
        assertThat(row.getTokenHash()).hasSize(64); // sha256 hex
    }

    @Test
    void unknownTokenResolvesToEmpty() {
        assertThat(sessionService.resolve("not-a-real-token")).isEmpty();
    }

    @Test
    void nullOrBlankTokenResolvesToEmptyWithoutTouchingTheDatabase() {
        assertThat(sessionService.resolve(null)).isEmpty();
        assertThat(sessionService.resolve("")).isEmpty();
    }

    @Test
    void tamperedTokenOneByteFlippedResolvesToEmpty() {
        SessionService.Created created = sessionService.create(userId, "TestAgent/1.0", "iphash");
        char[] chars = created.rawToken().toCharArray();
        chars[0] = chars[0] == 'a' ? 'b' : 'a';
        String tampered = new String(chars);

        assertThat(sessionService.resolve(tampered)).isEmpty();
    }

    @Test
    void revokedSessionResolvesToEmpty() {
        SessionService.Created created = sessionService.create(userId, "TestAgent/1.0", "iphash");
        sessionService.revoke(created.sessionId(), "LOGOUT");

        assertThat(sessionService.resolve(created.rawToken())).isEmpty();
    }

    @Test
    void logoutTwiceIsIdempotent() {
        SessionService.Created created = sessionService.create(userId, "TestAgent/1.0", "iphash");
        sessionService.revoke(created.sessionId(), "LOGOUT");
        sessionService.revoke(created.sessionId(), "LOGOUT"); // must not throw

        assertThat(sessionService.resolve(created.rawToken())).isEmpty();
    }

    @Test
    void idleExpiredSessionResolvesToEmptyAndIsMarkedRevokedWithReason() {
        SessionService.Created created = sessionService.create(userId, "TestAgent/1.0", "iphash");
        // sessionRepository.touch(...) is a custom @Modifying query with no
        // enclosing @Transactional here (unlike inside SessionServiceImpl) —
        // its flush has no active transaction to flush against. Go through
        // the entity + saveAndFlush instead, same as the absolute-expiry test below.
        UserSession row = sessionRepository.findById(created.sessionId()).orElseThrow();
        row.setIdleExpiresAt(clock.instant().minusSeconds(1));
        sessionRepository.saveAndFlush(row);

        assertThat(sessionService.resolve(created.rawToken())).isEmpty();

        UserSession reloaded = sessionRepository.findById(created.sessionId()).orElseThrow();
        assertThat(reloaded.getRevokedAt()).isNotNull();
        assertThat(reloaded.getRevokedReason()).isEqualTo("IDLE_EXPIRED");
    }

    @Test
    void absoluteExpiredSessionResolvesToEmptyAndIsMarkedRevokedWithReason() {
        SessionService.Created created = sessionService.create(userId, "TestAgent/1.0", "iphash");
        UserSession row = sessionRepository.findById(created.sessionId()).orElseThrow();
        row.setAbsoluteExpiresAt(clock.instant().minusSeconds(1));
        sessionRepository.saveAndFlush(row);

        assertThat(sessionService.resolve(created.rawToken())).isEmpty();

        UserSession reloaded = sessionRepository.findById(created.sessionId()).orElseThrow();
        assertThat(reloaded.getRevokedAt()).isNotNull();
        assertThat(reloaded.getRevokedReason()).isEqualTo("ABSOLUTE_EXPIRED");
    }

    @Test
    void rotateChangesTheHashButKeepsTheSameSessionRow() {
        SessionService.Created created = sessionService.create(userId, "TestAgent/1.0", "iphash");

        SessionService.Rotated rotated = sessionService.rotate(created.sessionId());

        assertThat(sessionService.resolve(created.rawToken())).isEmpty(); // old token dead
        assertThat(sessionService.resolve(rotated.rawToken())).isPresent(); // new token live, same session id
        assertThat(sessionService.resolve(rotated.rawToken()).get().sessionId()).isEqualTo(created.sessionId());
    }

    @Test
    void revokeAllExceptLeavesOnlyTheNamedSessionActive() {
        SessionService.Created keep = sessionService.create(userId, "A", "iphash");
        SessionService.Created other1 = sessionService.create(userId, "B", "iphash");
        SessionService.Created other2 = sessionService.create(userId, "C", "iphash");

        sessionService.revokeAllExcept(userId, keep.sessionId(), "PASSWORD_CHANGE");

        assertThat(sessionService.resolve(keep.rawToken())).isPresent();
        assertThat(sessionService.resolve(other1.rawToken())).isEmpty();
        assertThat(sessionService.resolve(other2.rawToken())).isEmpty();
    }

    @Test
    void elventhActiveSessionEvictsTheOldest() {
        SessionService.Created oldest = sessionService.create(userId, "0", "iphash");
        for (int i = 1; i < SessionService.MAX_SESSIONS_PER_USER; i++) {
            sessionService.create(userId, String.valueOf(i), "iphash");
        }
        assertThat(sessionRepository.countByUserIdAndRevokedAtIsNull(userId)).isEqualTo(SessionService.MAX_SESSIONS_PER_USER);

        sessionService.create(userId, "eleventh", "iphash");

        assertThat(sessionService.resolve(oldest.rawToken())).isEmpty();
        assertThat(sessionRepository.countByUserIdAndRevokedAtIsNull(userId)).isEqualTo(SessionService.MAX_SESSIONS_PER_USER);
    }

    @Test
    void listActiveExcludesRevokedSessions() {
        SessionService.Created live = sessionService.create(userId, "live", "iphash");
        SessionService.Created revoked = sessionService.create(userId, "revoked", "iphash");
        sessionService.revoke(revoked.sessionId(), "LOGOUT");

        List<UserSession> active = sessionService.listActive(userId);

        assertThat(active).extracting(UserSession::getId).containsExactly(live.sessionId());
    }

    @Test
    void revokeOwnedRejectsASessionBelongingToSomeoneElse() {
        UUID otherUserId = TestUsers.createActor(userRepository, idGenerator, clock).userId();
        SessionService.Created created = sessionService.create(userId, "mine", "iphash");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sessionService.revokeOwned(created.sessionId(), otherUserId))
                .isInstanceOf(com.deepon.smartdocs.user.exception.SessionNotFoundException.class);

        assertThat(sessionService.resolve(created.rawToken())).isPresent(); // untouched
    }

    @Test
    void revokeOwnedByTheCorrectUserSucceeds() {
        SessionService.Created created = sessionService.create(userId, "mine", "iphash");

        sessionService.revokeOwned(created.sessionId(), userId);

        assertThat(sessionService.resolve(created.rawToken())).isEmpty();
    }
}
