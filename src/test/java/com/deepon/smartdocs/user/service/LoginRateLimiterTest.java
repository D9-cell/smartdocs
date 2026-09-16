package com.deepon.smartdocs.user.service;

import com.deepon.smartdocs.user.exception.RateLimitedException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginRateLimiterTest {

    /** A {@link Clock} whose instant can be advanced, so window-expiry logic doesn't need to wait real minutes. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final LoginRateLimiter limiter = new LoginRateLimiter(clock);

    @Test
    void checkLoginRatePassesUnderTheThreshold() {
        for (int i = 0; i < 19; i++) {
            limiter.recordLoginFailure("ip-a");
        }
        limiter.checkLoginRate("ip-a"); // 19 recorded, threshold is 20 — must not throw
    }

    @Test
    void checkLoginRateThrowsAtTwentyFailuresWithinTheWindow() {
        for (int i = 0; i < 20; i++) {
            limiter.recordLoginFailure("ip-a");
        }
        assertThatThrownBy(() -> limiter.checkLoginRate("ip-a")).isInstanceOf(RateLimitedException.class);
    }

    @Test
    void differentIpsAreTrackedIndependently() {
        for (int i = 0; i < 20; i++) {
            limiter.recordLoginFailure("ip-a");
        }
        limiter.checkLoginRate("ip-b"); // untouched IP, must not throw
    }

    @Test
    void failuresOutsideTheFifteenMinuteWindowAreForgotten() {
        for (int i = 0; i < 20; i++) {
            limiter.recordLoginFailure("ip-a");
        }
        clock.advance(Duration.ofMinutes(16));
        limiter.checkLoginRate("ip-a"); // all 20 have aged out — must not throw
    }

    @Test
    void registerRateLimitTripsAtTheFourthAttemptWithinAnHour() {
        limiter.checkAndRecordRegisterAttempt("ip-a");
        limiter.checkAndRecordRegisterAttempt("ip-a");
        limiter.checkAndRecordRegisterAttempt("ip-a");
        assertThatThrownBy(() -> limiter.checkAndRecordRegisterAttempt("ip-a"))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void rateLimitedExceptionCarriesAPositiveRetryAfter() {
        for (int i = 0; i < 20; i++) {
            limiter.recordLoginFailure("ip-a");
        }
        assertThatThrownBy(() -> limiter.checkLoginRate("ip-a"))
                .isInstanceOf(RateLimitedException.class)
                .satisfies(ex -> assertThat(((RateLimitedException) ex).getRetryAfterSeconds()).isPositive());
    }
}
