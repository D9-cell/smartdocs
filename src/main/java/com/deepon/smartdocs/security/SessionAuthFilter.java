package com.deepon.smartdocs.security;

import com.deepon.smartdocs.common.Actor;
import com.deepon.smartdocs.user.service.SessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

/**
 * Cookie to {@link Actor}. Deliberately thin (design doc section 6.2): a
 * missing cookie leaves the request anonymous and lets it continue — some
 * paths (login, register) are public. It's {@link com.deepon.smartdocs.common.ActorArgumentResolver}
 * that turns "no Actor available" into 401 for the controller methods that
 * actually require one.
 *
 * A present but invalid token (unknown, tampered, revoked, expired) is
 * treated the same as no cookie for this request, except the now-useless
 * cookie is proactively cleared so the browser stops sending it.
 */
@Component
@Order(4)
public class SessionAuthFilter extends HttpFilter {

    public static final String ACTOR_ATTRIBUTE = "com.deepon.smartdocs.actor";
    public static final String SESSION_ID_ATTRIBUTE = "com.deepon.smartdocs.sessionId";

    private final SessionService sessionService;
    private final CookieSupport cookieSupport;

    public SessionAuthFilter(SessionService sessionService, CookieSupport cookieSupport) {
        this.sessionService = sessionService;
        this.cookieSupport = cookieSupport;
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        Optional<String> rawToken = readCookie(request);

        if (rawToken.isPresent()) {
            Optional<SessionService.Resolved> resolved = sessionService.resolve(rawToken.get());
            if (resolved.isPresent()) {
                request.setAttribute(ACTOR_ATTRIBUTE, resolved.get().actor());
                request.setAttribute(SESSION_ID_ATTRIBUTE, resolved.get().sessionId());
            } else {
                response.addHeader(HttpHeaders.SET_COOKIE, cookieSupport.clear());
            }
        }

        chain.doFilter(request, response);
    }

    private Optional<String> readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        // Two sid cookies sent: take the first, don't try every candidate (design doc section 10.3).
        for (Cookie cookie : cookies) {
            if (SessionService.COOKIE_NAME.equals(cookie.getName())) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }
}
