package com.deepon.smartdocs.security;

/**
 * Shared "does this Origin header match this app's own origin" logic, used
 * by both {@link OriginGuardFilter} (unsafe REST methods) and the Stage 2
 * WebSocket handshake interceptor — the same-origin policy does not cover
 * either a forged cross-site POST or a cross-site WebSocket connection
 * attempt, so both entry points need the identical check (design doc D7).
 */
public final class OriginMatcher {

    private OriginMatcher() {
    }

    public static String expectedOrigin(String scheme, String host, int port) {
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return defaultPort ? scheme + "://" + host : scheme + "://" + host + ":" + port;
    }

    /** {@code null} or blank {@code origin} is treated as a match — non-browser clients send neither header and are not the threat this check defends against. */
    public static boolean matches(String expectedOrigin, String origin) {
        if (origin == null || origin.isBlank()) {
            return true;
        }
        return expectedOrigin.equalsIgnoreCase(origin.trim());
    }
}
