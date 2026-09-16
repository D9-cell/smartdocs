package com.deepon.smartdocs.security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHasherTest {

    private final PasswordHasher passwordHasher = new PasswordHasher(new SimpleMeterRegistry());

    @Test
    void encodeThenMatchesRoundTrips() {
        String hash = passwordHasher.encode("correct horse battery staple");

        assertThat(passwordHasher.matches("correct horse battery staple", hash)).isTrue();
        assertThat(passwordHasher.matches("wrong password", hash)).isFalse();
    }

    @Test
    void encodedHashCarriesTheArgon2Prefix() {
        String hash = passwordHasher.encode("correct horse battery staple");
        assertThat(hash).startsWith("{argon2}");
    }

    @Test
    void twoEncodesOfTheSamePasswordProduceDifferentHashes() {
        // Different random salt each time — this is the point of a salted KDF.
        String first = passwordHasher.encode("same password");
        String second = passwordHasher.encode("same password");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void verifyAgainstDummyHashNeverThrowsRegardlessOfInput() {
        // Only proves it runs a real verification and doesn't blow up; the
        // timing-flatness property itself is asserted at the AuthService level.
        passwordHasher.verifyAgainstDummyHash("anything at all");
    }
}
