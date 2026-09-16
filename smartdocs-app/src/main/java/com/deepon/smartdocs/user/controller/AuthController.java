package com.deepon.smartdocs.user.controller;

import com.deepon.smartdocs.common.Actor;
import com.deepon.smartdocs.security.CookieSupport;
import com.deepon.smartdocs.security.SessionAuthFilter;
import com.deepon.smartdocs.user.dto.ChangePasswordRequest;
import com.deepon.smartdocs.user.dto.LoginRequest;
import com.deepon.smartdocs.user.dto.RegisterRequest;
import com.deepon.smartdocs.user.dto.SessionResponse;
import com.deepon.smartdocs.user.dto.UserResponse;
import com.deepon.smartdocs.user.entity.UserSession;
import com.deepon.smartdocs.user.service.AuthService;
import com.deepon.smartdocs.user.service.SessionService;
import com.deepon.smartdocs.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Thin by design, same as {@code DocumentController}: HTTP shape and cookie
 * handling only, no business rules (design doc section 6.2). Every response
 * here also carries {@code Cache-Control: no-store} (design doc section 9)
 * so an intermediary never caches a body containing session-adjacent data.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;
    private final SessionService sessionService;
    private final CookieSupport cookieSupport;
    private final Clock clock;

    public AuthController(AuthService authService, UserService userService, SessionService sessionService,
                           CookieSupport cookieSupport, Clock clock) {
        this.authService = authService;
        this.userService = userService;
        this.sessionService = sessionService;
        this.cookieSupport = cookieSupport;
        this.clock = clock;
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        AuthService.AuthResult result = authService.register(
                request.email(), request.password(), request.displayName(),
                httpRequest.getHeader("User-Agent"), httpRequest.getRemoteAddr());
        return ResponseEntity.status(201)
                .headers(noStore())
                .header(HttpHeaders.SET_COOKIE, cookieSupport.issue(result.rawToken(), result.absoluteExpiresAt(), clock.instant()))
                .body(UserResponse.from(result.user()));
    }

    @PostMapping("/login")
    public ResponseEntity<UserResponse> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        UUID existingSessionId = (UUID) httpRequest.getAttribute(SessionAuthFilter.SESSION_ID_ATTRIBUTE);
        AuthService.AuthResult result = authService.login(
                request.email(), request.password(),
                httpRequest.getHeader("User-Agent"), httpRequest.getRemoteAddr(), existingSessionId);
        return ResponseEntity.ok()
                .headers(noStore())
                .header(HttpHeaders.SET_COOKIE, cookieSupport.issue(result.rawToken(), result.absoluteExpiresAt(), clock.instant()))
                .body(UserResponse.from(result.user()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        UUID sessionId = (UUID) httpRequest.getAttribute(SessionAuthFilter.SESSION_ID_ATTRIBUTE);
        authService.logout(sessionId);
        return ResponseEntity.noContent()
                .headers(noStore())
                .header(HttpHeaders.SET_COOKIE, cookieSupport.clear())
                .build();
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(Actor actor) {
        return ResponseEntity.ok()
                .headers(noStore())
                .body(UserResponse.from(userService.getActiveById(actor.userId())));
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(Actor actor, HttpServletRequest httpRequest,
                                                @RequestBody ChangePasswordRequest request) {
        UUID sessionId = (UUID) httpRequest.getAttribute(SessionAuthFilter.SESSION_ID_ATTRIBUTE);
        SessionService.Rotated rotated = authService.changePassword(actor, sessionId, request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent()
                .headers(noStore())
                .header(HttpHeaders.SET_COOKIE, cookieSupport.issue(rotated.rawToken(), rotated.absoluteExpiresAt(), clock.instant()))
                .build();
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<SessionResponse>> listSessions(Actor actor, HttpServletRequest httpRequest) {
        UUID currentSessionId = (UUID) httpRequest.getAttribute(SessionAuthFilter.SESSION_ID_ATTRIBUTE);
        List<SessionResponse> sessions = sessionService.listActive(actor.userId()).stream()
                .map((UserSession s) -> SessionResponse.from(s, currentSessionId))
                .toList();
        return ResponseEntity.ok().headers(noStore()).body(sessions);
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> revokeSession(Actor actor, @PathVariable UUID id) {
        sessionService.revokeOwned(id, actor.userId());
        return ResponseEntity.noContent().headers(noStore()).build();
    }

    private HttpHeaders noStore() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore());
        return headers;
    }
}
