package com.deepon.smartdocs.security;

import com.deepon.smartdocs.user.exception.AuthBusyException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Argon2id at OWASP baseline parameters (design doc section 6.4): memory
 * 19456 KiB, iterations 2, parallelism 1, salt 16 bytes, hash 32 bytes.
 * Encoded via {@link DelegatingPasswordEncoder} so every stored hash carries
 * an {@code {argon2}} prefix and a future parameter bump rehashes lazily on
 * next successful login.
 *
 * Each hash allocates ~19 MiB. A {@link Semaphore} caps concurrent hashing at
 * 4 so a login flood can't exhaust heap — rate limiting alone doesn't help
 * here, because the limiter only runs after a burst has already arrived.
 * A timed-out acquire surfaces as {@link AuthBusyException} (503 AUTH_BUSY).
 */
@Component
public class PasswordHasher {

    private static final int SEMAPHORE_PERMITS = 4;
    private static final long ACQUIRE_TIMEOUT_SECONDS = 2;
    private static final String DUMMY_PASSWORD = "dummy-password-for-constant-time-comparison";

    private final PasswordEncoder encoder;
    private final Semaphore semaphore = new Semaphore(SEMAPHORE_PERMITS);
    private final String dummyHash;
    private final MeterRegistry meterRegistry;

    public PasswordHasher(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Map<String, PasswordEncoder> encoders = Map.of("argon2", new Argon2PasswordEncoder(16, 32, 1, 19456, 2));
        this.encoder = new DelegatingPasswordEncoder("argon2", encoders);
        // Computed once at startup, not per request — a dummy hash is a fixed
        // comparison target, not something that needs to vary per call.
        this.dummyHash = encoder.encode(DUMMY_PASSWORD);
    }

    public String encode(String rawPassword) {
        return withSemaphore(() -> encoder.encode(rawPassword));
    }

    public boolean matches(String rawPassword, String encodedHash) {
        return withSemaphore(() -> encoder.matches(rawPassword, encodedHash));
    }

    /**
     * Runs a real Argon2id verification against a fixed dummy hash and
     * discards the result. Called on the unknown-email login path so
     * response time carries no signal about whether the account exists
     * (design doc section 5.2, 9).
     */
    public void verifyAgainstDummyHash(String rawPassword) {
        withSemaphore(() -> encoder.matches(rawPassword, dummyHash));
    }

    private <T> T withSemaphore(Supplier<T> operation) {
        // auth_hash_wait_seconds (design doc section 12): time spent waiting
        // for a permit, not the hash itself — this is what shows semaphore
        // pressure building under a login flood before requests start failing.
        Timer.Sample waitSample = Timer.start(meterRegistry);
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(ACQUIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthBusyException();
        } finally {
            waitSample.stop(meterRegistry.timer("auth_hash_wait_seconds"));
        }
        if (!acquired) {
            throw new AuthBusyException();
        }
        try {
            return operation.get();
        } finally {
            semaphore.release();
        }
    }
}
