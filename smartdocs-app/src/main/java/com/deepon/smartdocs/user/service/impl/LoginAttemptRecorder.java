package com.deepon.smartdocs.user.service.impl;

import com.deepon.smartdocs.user.entity.AppUser;
import com.deepon.smartdocs.user.entity.LoginAttempt;
import com.deepon.smartdocs.user.repository.LoginAttemptRepository;
import com.deepon.smartdocs.user.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A dedicated bean so {@code REQUIRES_NEW} propagation actually applies
 * (Spring's proxy-based {@code @Transactional} has no effect on a
 * self-invoked private method). This exists for exactly the failure mode
 * design doc section 6.5 calls out for the attempt log — "If the attempt
 * row joins the failing transaction it rolls back with it, and the rate
 * limiter counts zero forever" — and the same is true of the lockout
 * bookkeeping on {@code app_user}: {@code AuthServiceImpl.login} always
 * ends a failed attempt by throwing, which marks its own transaction for
 * rollback. Any {@code app_user} mutation that must survive that (the
 * failure-count increment, the lock, the self-heal) has to commit
 * independently, here, before the throw.
 *
 * {@code REQUIRES_NEW} isn't free: it checks out a second pooled connection
 * for as long as the caller's own transaction is still open (design doc
 * section 4.1: HikariCP pool 10). {@link #record} stays {@code REQUIRES_NEW}
 * because every one of its callers throws afterward and needs the write to
 * survive that rollback — but a successful login doesn't throw, so
 * {@link #recordSuccess} participates in the caller's own transaction
 * instead. Using {@code REQUIRES_NEW} unconditionally here would mean every
 * concurrent login holds two connections at once, and enough concurrent
 * logins to fill the pool with outer transactions would then deadlock it —
 * every one of those still needing a second, nested connection that can
 * never come free.
 */
@Component
public class LoginAttemptRecorder {

    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);
    private static final int LOCKOUT_THRESHOLD = 5;

    private final LoginAttemptRepository loginAttemptRepository;
    private final UserRepository userRepository;
    private final MeterRegistry meterRegistry;

    public LoginAttemptRecorder(LoginAttemptRepository loginAttemptRepository, UserRepository userRepository,
                                 MeterRegistry meterRegistry) {
        this.loginAttemptRepository = loginAttemptRepository;
        this.userRepository = userRepository;
        this.meterRegistry = meterRegistry;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String emailNormalized, String ipHash, String outcome, Instant now) {
        loginAttemptRepository.save(new LoginAttempt(emailNormalized, ipHash, outcome, now));
        countOutcome(outcome);
    }

    /** Success path only — nothing rolls back a successful login, so this rides the caller's own transaction rather than opening a second connection. */
    @Transactional
    public void recordSuccess(String emailNormalized, String ipHash, Instant now) {
        loginAttemptRepository.save(new LoginAttempt(emailNormalized, ipHash, LoginAttempt.OUTCOME_SUCCESS, now));
        countOutcome(LoginAttempt.OUTCOME_SUCCESS);
    }

    /** Increments the failure counter, locks the account if the threshold is reached, and logs the attempt — atomically, independent of the caller's outcome. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordBadPasswordFailure(UUID userId, String emailNormalized, String ipHash, Instant now) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setFailedLoginCount(user.getFailedLoginCount() + 1);
            if (user.getFailedLoginCount() >= LOCKOUT_THRESHOLD) {
                user.setStatus(AppUser.STATUS_LOCKED);
                user.setLockedUntil(now.plus(LOCKOUT_DURATION));
            }
            userRepository.save(user);
        });
        loginAttemptRepository.save(new LoginAttempt(emailNormalized, ipHash, LoginAttempt.OUTCOME_BAD_PASSWORD, now));
        countOutcome(LoginAttempt.OUTCOME_BAD_PASSWORD);
    }

    /** design doc section 12: auth_login_total{outcome} — a credential-stuffing spike shows up here as a jump in BAD_PASSWORD. */
    private void countOutcome(String outcome) {
        meterRegistry.counter("auth_login_total", "outcome", outcome).increment();
    }

    /** Clears an expired lock immediately, independent of whatever this login attempt goes on to do. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearExpiredLock(UUID userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setStatus(AppUser.STATUS_ACTIVE);
            user.setLockedUntil(null);
            user.setFailedLoginCount(0);
            userRepository.save(user);
        });
    }
}
