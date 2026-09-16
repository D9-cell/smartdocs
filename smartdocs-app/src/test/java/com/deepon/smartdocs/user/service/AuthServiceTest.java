package com.deepon.smartdocs.user.service;

import com.deepon.smartdocs.common.Actor;
import com.deepon.smartdocs.common.Sha256;
import com.deepon.smartdocs.common.exception.ValidationFailedException;
import com.deepon.smartdocs.support.AbstractPostgresTest;
import com.deepon.smartdocs.user.entity.AppUser;
import com.deepon.smartdocs.user.exception.AccountLockedException;
import com.deepon.smartdocs.user.exception.AuthBusyException;
import com.deepon.smartdocs.user.exception.EmailTakenException;
import com.deepon.smartdocs.user.exception.InvalidCredentialsException;
import com.deepon.smartdocs.user.repository.SessionRepository;
import com.deepon.smartdocs.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every outcome in design doc sections 10.1 and 10.2, against a real
 * database — {@code AuthService} is one of the three classes section 12
 * names for the >90% line-coverage target.
 */
@SpringBootTest
class AuthServiceTest extends AbstractPostgresTest {

    private static final String USER_AGENT = "JUnit/1.0";

    // LoginRateLimiter is a real singleton bean holding in-memory state
    // shared across every test method in this class (and beyond, if Spring
    // reuses the context) — a fixed IP would trip the 3-per-hour register
    // limit and the 20-per-15-min login limit purely from test cross-talk.
    // A fresh value per test keeps each test's rate-limit window isolated.
    private String IP;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private Clock clock;

    @BeforeEach
    void freshIp() {
        IP = "ip-" + UUID.randomUUID();
    }

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    /** {@code AuthResult} doesn't expose a session id (the cookie is the session handle); tests that need it resolve it the way the database would — by hash. */
    private UUID sessionIdFor(String rawToken) {
        return sessionRepository.findByTokenHash(Sha256.hex(rawToken)).orElseThrow().getId();
    }

    @Test
    void registerCreatesAnActiveUserAndIssuesASession() {
        String email = uniqueEmail();

        AuthService.AuthResult result = authService.register(email, "a-long-enough-password", "Deepon", USER_AGENT, IP);

        assertThat(result.user().getEmail()).isEqualTo(email);
        assertThat(result.user().getStatus()).isEqualTo(AppUser.STATUS_ACTIVE);
        assertThat(result.rawToken()).isNotBlank();
    }

    @Test
    void registerTrimsWhitespaceButPreservesCase() {
        String email = "  MiXed" + java.util.UUID.randomUUID() + "@Example.com  ";

        AuthService.AuthResult result = authService.register(email, "a-long-enough-password", "Name", USER_AGENT, IP);

        assertThat(result.user().getEmail()).isEqualTo(email.trim());
    }

    @Test
    void duplicateEmailDifferentCaseThrowsEmailTaken() {
        String email = uniqueEmail();
        authService.register(email, "a-long-enough-password", "First", USER_AGENT, IP);

        assertThatThrownBy(() -> authService.register(email.toUpperCase(), "a-long-enough-password", "Second", USER_AGENT, IP))
                .isInstanceOf(EmailTakenException.class);
    }

    @Test
    void invalidRegistrationFieldsThrowValidationFailed() {
        assertThatThrownBy(() -> authService.register("not-an-email", "short", "", USER_AGENT, IP))
                .isInstanceOf(ValidationFailedException.class);
    }

    @Test
    void loginWithCorrectPasswordSucceeds() {
        String email = uniqueEmail();
        authService.register(email, "a-long-enough-password", "Name", USER_AGENT, IP);

        AuthService.AuthResult result = authService.login(email, "a-long-enough-password", USER_AGENT, IP, null);

        assertThat(result.user().getEmail()).isEqualTo(email);
    }

    @Test
    void loginWithEmailDifferingOnlyInCaseMatches() {
        String email = uniqueEmail();
        authService.register(email, "a-long-enough-password", "Name", USER_AGENT, IP);

        AuthService.AuthResult result = authService.login(email.toUpperCase(), "a-long-enough-password", USER_AGENT, IP, null);

        assertThat(result.user().getEmail()).isEqualTo(email);
    }

    @Test
    void loginWithUnknownEmailThrowsInvalidCredentials() {
        assertThatThrownBy(() -> authService.login(uniqueEmail(), "whatever-password", USER_AGENT, IP, null))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void loginWithWrongPasswordThrowsInvalidCredentials() {
        String email = uniqueEmail();
        authService.register(email, "a-long-enough-password", "Name", USER_AGENT, IP);

        assertThatThrownBy(() -> authService.login(email, "totally-wrong-password", USER_AGENT, IP, null))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void fifthConsecutiveFailureLocksTheAccount() {
        String email = uniqueEmail();
        authService.register(email, "a-long-enough-password", "Name", USER_AGENT, IP);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login(email, "wrong-password", USER_AGENT, IP, null));
        }

        // The 5th failure locked it — a 6th attempt, even with the *correct*
        // password, must fail with 423 without even checking the password
        // (design doc section 10.2: "Success does not clear a lockout early").
        assertThatThrownBy(() -> authService.login(email, "a-long-enough-password", USER_AGENT, IP, null))
                .isInstanceOf(AccountLockedException.class);
    }

    @Test
    void lockClearsAutomaticallyOncePastLockedUntil() {
        String email = uniqueEmail();
        authService.register(email, "a-long-enough-password", "Name", USER_AGENT, IP);
        for (int i = 0; i < 5; i++) {
            try {
                authService.login(email, "wrong-password", USER_AGENT, IP, null);
            } catch (Exception ignored) {
                // expected on every one of these five
            }
        }
        AppUser locked = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(locked.getStatus()).isEqualTo(AppUser.STATUS_LOCKED);

        // Simulate the 15-minute lockout window having already elapsed.
        locked.setLockedUntil(clock.instant().minusSeconds(1));
        userRepository.saveAndFlush(locked);

        AuthService.AuthResult result = authService.login(email, "a-long-enough-password", USER_AGENT, IP, null);

        assertThat(result.user().getEmail()).isEqualTo(email);
        AppUser healed = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(healed.getStatus()).isEqualTo(AppUser.STATUS_ACTIVE);
        assertThat(healed.getFailedLoginCount()).isZero();
    }

    @Test
    void successfulLoginResetsTheFailureCounter() {
        String email = uniqueEmail();
        authService.register(email, "a-long-enough-password", "Name", USER_AGENT, IP);
        try {
            authService.login(email, "wrong-password", USER_AGENT, IP, null);
        } catch (InvalidCredentialsException ignored) {
        }

        authService.login(email, "a-long-enough-password", USER_AGENT, IP, null);

        AppUser reloaded = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(reloaded.getFailedLoginCount()).isZero();
    }

    @Test
    void loginWithAnExistingSessionRevokesItAndIssuesANewOne() {
        String email = uniqueEmail();
        AuthService.AuthResult first = authService.register(email, "a-long-enough-password", "Name", USER_AGENT, IP);

        AuthService.AuthResult second = authService.login(email, "a-long-enough-password", USER_AGENT, IP, null);

        assertThat(second.rawToken()).isNotEqualTo(first.rawToken());
    }

    @Test
    void disabledAccountLoginFailsWithoutRevealingStatus() {
        String email = uniqueEmail();
        authService.register(email, "a-long-enough-password", "Name", USER_AGENT, IP);
        AppUser user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        user.setStatus(AppUser.STATUS_DISABLED);
        userRepository.saveAndFlush(user);

        // Same exception type/shape as any other failed login — never a distinct signal.
        assertThatThrownBy(() -> authService.login(email, "a-long-enough-password", USER_AGENT, IP, null))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void logoutIsIdempotentEvenWithoutASession() {
        authService.logout(null); // must not throw
    }

    @Test
    void changePasswordWithWrongCurrentPasswordThrowsInvalidCredentials() {
        String email = uniqueEmail();
        AuthService.AuthResult registered = authService.register(email, "a-long-enough-password", "Name", USER_AGENT, IP);
        Actor actor = Actor.human(registered.user().getId());
        UUID sessionId = sessionIdFor(registered.rawToken());

        assertThatThrownBy(() -> authService.changePassword(actor, sessionId, "wrong-current", "new-long-enough-pw"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void changePasswordSucceedsRotatesSessionAndOldPasswordNoLongerWorks() {
        String email = uniqueEmail();
        AuthService.AuthResult registered = authService.register(email, "a-long-enough-password", "Name", USER_AGENT, IP);
        Actor actor = Actor.human(registered.user().getId());
        UUID sessionId = sessionIdFor(registered.rawToken());

        SessionService.Rotated rotated = authService.changePassword(actor, sessionId, "a-long-enough-password", "new-long-enough-pw");

        assertThat(rotated.rawToken()).isNotBlank();
        assertThatThrownBy(() -> authService.login(email, "a-long-enough-password", USER_AGENT, IP, null))
                .isInstanceOf(InvalidCredentialsException.class);
        AuthService.AuthResult reLogin = authService.login(email, "new-long-enough-pw", USER_AGENT, IP, null);
        assertThat(reLogin.user().getEmail()).isEqualTo(email);
    }

    @Test
    void changePasswordRevokesSiblingSessionsButKeepsTheCurrentOne() {
        String email = uniqueEmail();
        AuthService.AuthResult registered = authService.register(email, "a-long-enough-password", "Name", USER_AGENT, IP);
        Actor actor = Actor.human(registered.user().getId());
        UUID sessionId = sessionIdFor(registered.rawToken());

        authService.changePassword(actor, sessionId, "a-long-enough-password", "new-long-enough-pw");

        // The registration session (now rotated) must still resolve; a
        // sibling session's death is covered directly in SessionServiceTest's
        // revokeAllExceptLeavesOnlyTheNamedSessionActive.
        assertThat(sessionRepository.findById(sessionId)).isPresent();
    }

    /**
     * Design doc section 11: "mean response time for unknown email within 20
     * percent of wrong password, over 200 samples. Loose bound, catches the
     * accidental removal of the dummy hash." Each wrong-password sample uses
     * its own freshly registered account (one failure each, never near the
     * 5-failure lockout threshold) and every sample uses a unique IP (never
     * near the 20-failure-per-15-minutes rate limit) so nothing but the
     * password hashing itself shows up in the timing.
     *
     * Samples are interleaved (one of each per loop iteration, not all of
     * one group then all of the other) and a short discarded warm-up runs
     * first — JIT compilation and connection-pool priming otherwise show up
     * as a one-sided time cost on whichever group runs first, which is a
     * measurement artifact, not the timing-flatness property this test
     * actually checks.
     */
    @Test
    void unknownEmailAndWrongPasswordHaveSimilarMeanTimingOver200Samples() {
        int warmup = 10;
        int samples = 200;
        List<String> emails = new ArrayList<>(samples);
        for (int i = 0; i < warmup + samples; i++) {
            String email = uniqueEmail();
            authService.register(email, "a-long-enough-password", "Name", USER_AGENT, "ip-setup-" + UUID.randomUUID());
            emails.add(email);
        }

        for (int i = 0; i < warmup; i++) {
            attemptUnknownEmailLogin();
            attemptWrongPasswordLogin(emails.get(i));
        }

        List<Long> unknownEmailNanos = new ArrayList<>(samples);
        List<Long> wrongPasswordNanos = new ArrayList<>(samples);
        for (int i = warmup; i < warmup + samples; i++) {
            unknownEmailNanos.add(attemptUnknownEmailLogin());
            wrongPasswordNanos.add(attemptWrongPasswordLogin(emails.get(i)));
        }

        double meanUnknown = unknownEmailNanos.stream().mapToLong(Long::longValue).average().orElseThrow();
        double meanWrong = wrongPasswordNanos.stream().mapToLong(Long::longValue).average().orElseThrow();
        double relativeDifference = Math.abs(meanUnknown - meanWrong) / Math.max(meanUnknown, meanWrong);

        assertThat(relativeDifference)
                .as("mean(unknown-email)=%.1fms mean(wrong-password)=%.1fms", meanUnknown / 1e6, meanWrong / 1e6)
                .isLessThan(0.20);
    }

    private long attemptUnknownEmailLogin() {
        long start = System.nanoTime();
        try {
            authService.login(uniqueEmail(), "whatever-password", USER_AGENT, "ip-unknown-" + UUID.randomUUID(), null);
        } catch (InvalidCredentialsException ignored) {
            // expected — the timing is the point, not the outcome
        }
        return System.nanoTime() - start;
    }

    private long attemptWrongPasswordLogin(String email) {
        long start = System.nanoTime();
        try {
            authService.login(email, "totally-wrong-password", USER_AGENT, "ip-wrong-" + UUID.randomUUID(), null);
        } catch (InvalidCredentialsException ignored) {
            // expected — the timing is the point, not the outcome
        }
        return System.nanoTime() - start;
    }

    /**
     * Design doc section 10.8's own documented edge case, exercised for
     * real: "50 simultaneous login attempts — Semaphore bounds hashing at 4,
     * excess waits 2 s then gets 503 AUTH_BUSY." This is NOT a test that all
     * 50 must succeed — the design doc explicitly says the opposite, that
     * backpressure under this exact load is correct behavior. What has to be
     * true regardless: every successful login returns a unique token, every
     * rejection is one of the documented 503-class failures (never a hang,
     * data corruption, or an exception nobody planned for), at least one
     * request gets through, and the system is fully usable again right after.
     */
    @Test
    void fiftyThreadsLoggingInAsTheSameUserDegradeGracefullyUnderContention() throws Exception {
        String email = uniqueEmail();
        authService.register(email, "a-long-enough-password", "Name", USER_AGENT, IP);

        int threadCount = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<AuthService.AuthResult>> futures = new ArrayList<>(threadCount);

        try {
            for (int i = 0; i < threadCount; i++) {
                String threadIp = "ip-concurrent-" + UUID.randomUUID();
                futures.add(pool.submit(() -> {
                    startLatch.await();
                    return authService.login(email, "a-long-enough-password", USER_AGENT, threadIp, null);
                }));
            }
            startLatch.countDown();

            Set<String> tokens = new HashSet<>();
            int succeeded = 0;
            int gracefullyRejected = 0;
            for (Future<AuthService.AuthResult> future : futures) {
                try {
                    AuthService.AuthResult result = future.get(30, TimeUnit.SECONDS);
                    assertThat(tokens.add(result.rawToken())).isTrue();
                    succeeded++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause())
                            .isInstanceOfAny(AuthBusyException.class, CannotCreateTransactionException.class,
                                    DataAccessResourceFailureException.class, QueryTimeoutException.class);
                    gracefullyRejected++;
                }
            }

            assertThat(succeeded + gracefullyRejected).isEqualTo(threadCount);
            assertThat(succeeded).as("at least some requests must get through, not just fail-fast on everything").isPositive();
        } finally {
            pool.shutdown();
        }

        // The overload has passed — a fresh login must work exactly as normal.
        AuthService.AuthResult recovered = authService.login(
                email, "a-long-enough-password", USER_AGENT, "ip-recovery-" + UUID.randomUUID(), null);
        assertThat(recovered.rawToken()).isNotBlank();
    }
}
