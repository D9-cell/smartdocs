package com.deepon.smartdocs.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

/**
 * CSRF defense for the cookie-based session (design doc section 4.3 step 2,
 * section 9). Runs before {@link SessionAuthFilter}: rejecting here means the
 * session lookup never even happens for a forged cross-site request.
 *
 * A request runs the filter chain outside Spring MVC's exception-resolution
 * path, so a rejection is written directly as {@code problem+json} here —
 * the same reason {@code RequestSizeLimitingFilter} does the same thing —
 * rather than thrown for {@code GlobalExceptionHandler} to catch.
 */
@Component
@Order(3)
public class OriginGuardFilter extends HttpFilter {

    private static final Set<String> UNSAFE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (UNSAFE_METHODS.contains(request.getMethod()) && !isSameOrigin(request)) {
            writeRejected(response);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * {@code Sec-Fetch-Site: cross-site} rejects outright. Otherwise a
     * present {@code Origin} header must match this app's own origin. A
     * request with neither header — curl, integration tests, non-browser
     * clients — passes: they are not CSRF vectors (design doc section 10.6).
     */
    private boolean isSameOrigin(HttpServletRequest request) {
        String secFetchSite = request.getHeader("Sec-Fetch-Site");
        if ("cross-site".equalsIgnoreCase(secFetchSite)) {
            return false;
        }

        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return true;
        }

        String expected = expectedOrigin(request);
        return expected.equalsIgnoreCase(origin.trim());
    }

    private String expectedOrigin(HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return defaultPort ? scheme + "://" + host : scheme + "://" + host + ":" + port;
    }

    private void writeRejected(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"about:blank","title":"Origin rejected","status":403,\
                "detail":"Cross-site request rejected.","code":"ORIGIN_REJECTED"}\
                """);
    }
}
