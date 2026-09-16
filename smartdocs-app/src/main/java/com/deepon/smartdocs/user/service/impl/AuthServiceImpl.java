package com.deepon.smartdocs.user.service.impl;

import com.deepon.smartdocs.common.Actor;
import com.deepon.smartdocs.common.IdGenerator;
import com.deepon.smartdocs.security.IpHasher;
import com.deepon.smartdocs.security.PasswordHasher;
import com.deepon.smartdocs.user.UserValidator;
import com.deepon.smartdocs.user.entity.AppUser;
import com.deepon.smartdocs.user.entity.LoginAttempt;
import com.deepon.smartdocs.user.exception.AccountLockedException;
import com.deepon.smartdocs.user.exception.EmailTakenException;
import com.deepon.smartdocs.user.exception.InvalidCredentialsException;
import com.deepon.smartdocs.user.exception.SessionInvalidException;
import com.deepon.smartdocs.user.repository.UserRepository;
import com.deepon.smartdocs.user.service.AuthService;
import com.deepon.smartdocs.user.service.LoginRateLimiter;
import com.deepon.smartdocs.user.service.SessionService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);
    private static final int LOCKOUT_THRESHOLD = 5;

    private final UserRepository userRepository;
    private final SessionService sessionService;
    private final UserValidator userValidator;
    private final PasswordHasher passwordHasher;
    private final LoginRateLimiter loginRateLimiter;
    private final LoginAttemptRecorder loginAttemptRecorder;
    private final IpHasher ipHasher;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final Counter registerTotal;

    public AuthServiceImpl(UserRepository userRepository, SessionService sessionService, UserValidator userValidator,
                            PasswordHasher passwordHasher, LoginRateLimiter loginRateLimiter,
                            LoginAttemptRecorder loginAttemptRecorder, IpHasher ipHasher,
                            IdGenerator idGenerator, Clock clock, MeterRegistry meterRegistry) {
        this.userRepository = userRepository;
        this.sessionService = sessionService;
        this.userValidator = userValidator;
        this.passwordHasher = passwordHasher;
        this.loginRateLimiter = loginRateLimiter;
        this.loginAttemptRecorder = loginAttemptRecorder;
        this.ipHasher = ipHasher;
        this.idGenerator = idGenerator;
        this.clock = clock;
        // design doc section 12: auth_register_total — abuse detection.
        this.registerTotal = meterRegistry.counter("auth_register_total");
    }

    @Override
    @Transactional
    public AuthResult register(String rawEmail, String rawPassword, String rawDisplayName,
                                String userAgent, String ipAddress) {
        String ipHash = ipHasher.hash(ipAddress);
        loginRateLimiter.checkAndRecordRegisterAttempt(ipHash);

        UserValidator.RegistrationInput input = userValidator.validateRegistration(rawEmail, rawPassword, rawDisplayName);
        String passwordHash = passwordHasher.encode(rawPassword);
        Instant now = clock.instant();
        AppUser user = new AppUser(idGenerator.newId(), input.email(), input.displayName(), passwordHash, now, now);

        try {
            // saveAndFlush, not save: forces the unique-index violation to
            // surface here, inside this try block, rather than silently at
            // end-of-transaction commit where it can no longer be caught.
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new EmailTakenException();
        }
        registerTotal.increment();

        SessionService.Created created = sessionService.create(user.getId(), userAgent, ipHash);
        return new AuthResult(user, created.rawToken(), created.absoluteExpiresAt());
    }

    @Override
    @Transactional
    public AuthResult login(String rawEmail, String rawPassword, String userAgent, String ipAddress, UUID existingSessionId) {
        String ipHash = ipHasher.hash(ipAddress);
        loginRateLimiter.checkLoginRate(ipHash);

        String emailNormalized = rawEmail == null ? "" : rawEmail.trim();
        Instant now = clock.instant();
        Optional<AppUser> maybeUser = userRepository.findByEmailIgnoreCase(emailNormalized);

        if (maybeUser.isEmpty()) {
            // Real Argon2id verification against a fixed dummy hash keeps
            // response time flat — an attacker learns nothing about whether
            // the account exists (design doc section 5.2, 9).
            passwordHasher.verifyAgainstDummyHash(rawPassword);
            fail(emailNormalized, ipHash, LoginAttempt.OUTCOME_UNKNOWN_EMAIL, now);
        }

        AppUser user = maybeUser.get();

        if (AppUser.STATUS_LOCKED.equals(user.getStatus())) {
            if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
                fail(emailNormalized, ipHash, LoginAttempt.OUTCOME_LOCKED, now, new AccountLockedException());
            }
            // Lock window elapsed: self-heal, committed independently so it
            // survives even if this same attempt goes on to fail below.
            loginAttemptRecorder.clearExpiredLock(user.getId());
            user.setStatus(AppUser.STATUS_ACTIVE);
            user.setLockedUntil(null);
            user.setFailedLoginCount(0);
        }

        if (AppUser.STATUS_DISABLED.equals(user.getStatus())) {
            fail(emailNormalized, ipHash, LoginAttempt.OUTCOME_DISABLED, now);
        }

        if (!passwordHasher.matches(rawPassword, user.getPasswordHash())) {
            // This method always ends by throwing, which rolls back this
            // transaction — the lockout bookkeeping has to commit on its
            // own, not through this method's own (soon to be rolled back) one.
            loginAttemptRecorder.recordBadPasswordFailure(user.getId(), emailNormalized, ipHash, now);
            loginRateLimiter.recordLoginFailure(ipHash);
            throw new InvalidCredentialsException();
        }

        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        loginAttemptRecorder.recordSuccess(emailNormalized, ipHash, now);

        if (existingSessionId != null) {
            sessionService.revoke(existingSessionId, "RELOGIN");
        }
        SessionService.Created created = sessionService.create(user.getId(), userAgent, ipHash);
        return new AuthResult(user, created.rawToken(), created.absoluteExpiresAt());
    }

    @Override
    @Transactional
    public void logout(UUID sessionId) {
        if (sessionId != null) {
            sessionService.revoke(sessionId, "LOGOUT");
        }
    }

    @Override
    @Transactional
    public SessionService.Rotated changePassword(Actor actor, UUID currentSessionId, String currentPassword, String newPassword) {
        AppUser user = userRepository.findByIdAndDeletedAtIsNull(actor.userId())
                .orElseThrow(SessionInvalidException::new);

        if (!passwordHasher.matches(currentPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        userValidator.validatePasswordOrThrow(newPassword);

        user.setPasswordHash(passwordHasher.encode(newPassword));
        user.setPasswordChangedAt(clock.instant());
        userRepository.save(user);

        // Rotation defeats session fixation; revoking siblings is what
        // users expect a password change to do (design doc section 5.5).
        sessionService.revokeAllExcept(user.getId(), currentSessionId, "PASSWORD_CHANGE");
        return sessionService.rotate(currentSessionId);
    }

    private void fail(String emailNormalized, String ipHash, String outcome, Instant now) {
        fail(emailNormalized, ipHash, outcome, now, new InvalidCredentialsException());
    }

    private void fail(String emailNormalized, String ipHash, String outcome, Instant now, RuntimeException toThrow) {
        loginAttemptRecorder.record(emailNormalized, ipHash, outcome, now);
        loginRateLimiter.recordLoginFailure(ipHash);
        throw toThrow;
    }
}
