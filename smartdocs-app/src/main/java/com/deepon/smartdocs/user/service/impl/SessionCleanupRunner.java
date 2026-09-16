package com.deepon.smartdocs.user.service.impl;

import com.deepon.smartdocs.user.repository.LoginAttemptRepository;
import com.deepon.smartdocs.user.repository.SessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The actual cleanup deletes, isolated onto their own bean so
 * {@code @Transactional} is applied by Spring's proxy rather than skipped
 * via self-invocation from {@link SessionCleanupJob#run}.
 */
@Component
public class SessionCleanupRunner {

    private static final Duration SESSION_GRACE_PERIOD = Duration.ofDays(7);
    private static final Duration LOGIN_ATTEMPT_RETENTION = Duration.ofDays(30);

    public record Result(int sessionsDeleted, int attemptsDeleted) {
    }

    private final SessionRepository sessionRepository;
    private final LoginAttemptRepository loginAttemptRepository;
    private final Clock clock;

    public SessionCleanupRunner(SessionRepository sessionRepository, LoginAttemptRepository loginAttemptRepository, Clock clock) {
        this.sessionRepository = sessionRepository;
        this.loginAttemptRepository = loginAttemptRepository;
        this.clock = clock;
    }

    @Transactional
    public Result purge() {
        Instant now = clock.instant();
        int sessions = sessionRepository.deleteExpiredBefore(now.minus(SESSION_GRACE_PERIOD));
        int attempts = loginAttemptRepository.deleteOlderThan(now.minus(LOGIN_ATTEMPT_RETENTION));
        return new Result(sessions, attempts);
    }
}
