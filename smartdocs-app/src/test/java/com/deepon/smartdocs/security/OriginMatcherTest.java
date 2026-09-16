package com.deepon.smartdocs.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OriginMatcherTest {

    @Test
    void defaultHttpsPortIsOmittedFromExpectedOrigin() {
        assertThat(OriginMatcher.expectedOrigin("https", "app.example.com", 443)).isEqualTo("https://app.example.com");
    }

    @Test
    void defaultHttpPortIsOmittedFromExpectedOrigin() {
        assertThat(OriginMatcher.expectedOrigin("http", "localhost", 80)).isEqualTo("http://localhost");
    }

    @Test
    void nonDefaultPortIsIncluded() {
        assertThat(OriginMatcher.expectedOrigin("http", "localhost", 8080)).isEqualTo("http://localhost:8080");
    }

    @Test
    void matchingOriginPasses() {
        assertThat(OriginMatcher.matches("https://app.example.com", "https://app.example.com")).isTrue();
    }

    @Test
    void foreignOriginFails() {
        assertThat(OriginMatcher.matches("https://app.example.com", "https://evil.example.com")).isFalse();
    }

    @Test
    void matchIsCaseInsensitive() {
        assertThat(OriginMatcher.matches("https://app.example.com", "HTTPS://APP.EXAMPLE.COM")).isTrue();
    }

    @Test
    void missingOriginHeaderPasses() {
        // Non-browser clients — curl, integration tests — send neither
        // header and are not the threat this check defends against.
        assertThat(OriginMatcher.matches("https://app.example.com", null)).isTrue();
        assertThat(OriginMatcher.matches("https://app.example.com", "")).isTrue();
    }
}
