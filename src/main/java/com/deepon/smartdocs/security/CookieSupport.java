package com.deepon.smartdocs.security;

import com.deepon.smartdocs.user.service.SessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * The one place that formats the {@code sid} cookie (design doc section
 * 7.3). {@code secure} is driven by config, never by sniffing the request
 * scheme — set {@code smartdocs.security.cookie-secure=false} for local
 * http development only.
 */
@Component
public class CookieSupport {

    private final boolean secure;

    public CookieSupport(@Value("${smartdocs.security.cookie-secure:true}") boolean secure) {
        this.secure = secure;
    }

    /** {@code Max-Age} matches the session's absolute expiry — the cookie is a cache, the database is the source of truth. */
    public String issue(String rawToken, Instant absoluteExpiresAt, Instant now) {
        long maxAgeSeconds = Math.max(0, Duration.between(now, absoluteExpiresAt).getSeconds());
        return build(rawToken, maxAgeSeconds).toString();
    }

    public String clear() {
        return build("", 0).toString();
    }

    private ResponseCookie build(String value, long maxAgeSeconds) {
        return ResponseCookie.from(SessionService.COOKIE_NAME, value)
                .path("/")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .maxAge(maxAgeSeconds)
                .build();
    }
}
