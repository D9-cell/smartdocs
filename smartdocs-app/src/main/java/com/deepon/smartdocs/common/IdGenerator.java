package com.deepon.smartdocs.common;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * UUIDv7: a 48-bit millisecond timestamp in the top bits, then version and
 * variant bits, then 74 random bits. Time-ordered, so new rows land at the
 * tail of their index instead of scattering across it the way UUIDv4 does
 * (design doc section 8.1). Used for {@code app_user} and {@code user_session}
 * ids; Stage 0's random {@code UUID.randomUUID()} document/revision ids are
 * left alone — this stage doesn't touch that decision.
 */
@Component
public class IdGenerator {

    private final Clock clock;
    private final SecureRandom random;

    public IdGenerator(Clock clock) {
        this.clock = clock;
        this.random = new SecureRandom();
    }

    public UUID newId() {
        long millis = clock.millis();
        byte[] randomBytes = new byte[10];
        random.nextBytes(randomBytes);

        long msb = (millis & 0xFFFFFFFFFFFFL) << 16;
        msb |= 0x7000L; // version 7
        msb |= (randomBytes[0] & 0x0F) << 8;
        msb |= (randomBytes[1] & 0xFF);

        long lsb = 0L;
        lsb |= (0x2L << 62); // variant 10
        lsb |= ((long) (randomBytes[2] & 0x3F)) << 56;
        for (int i = 3; i < 10; i++) {
            lsb |= ((long) (randomBytes[i] & 0xFF)) << (8 * (9 - i));
        }

        return new UUID(msb, lsb);
    }
}
