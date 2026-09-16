package com.deepon.smartdocs.user.controller;

import com.deepon.smartdocs.common.Actor;
import com.deepon.smartdocs.common.ActorArgumentResolver;
import com.deepon.smartdocs.config.ClockConfig;
import com.deepon.smartdocs.config.WebConfig;
import com.deepon.smartdocs.security.CookieSupport;
import com.deepon.smartdocs.security.SessionAuthFilter;
import com.deepon.smartdocs.user.entity.AppUser;
import com.deepon.smartdocs.user.exception.AccountLockedException;
import com.deepon.smartdocs.user.exception.EmailTakenException;
import com.deepon.smartdocs.user.exception.InvalidCredentialsException;
import com.deepon.smartdocs.user.exception.RateLimitedException;
import com.deepon.smartdocs.user.service.AuthService;
import com.deepon.smartdocs.user.service.SessionService;
import com.deepon.smartdocs.user.service.UserService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Design doc section 7.1, 7.4: every auth endpoint's status code and cookie handling, against a mocked service layer. */
@WebMvcTest(AuthController.class)
@Import({WebConfig.class, ActorArgumentResolver.class, ClockConfig.class, SimpleMeterRegistry.class})
class AuthControllerWebMvcTest {

    private static final Actor ACTOR = Actor.human(UUID.fromString("22222222-2222-2222-2222-222222222222"));

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private UserService userService;

    @MockBean
    private SessionService sessionService;

    @MockBean
    private CookieSupport cookieSupport;

    private MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder builder) {
        return builder.requestAttr(SessionAuthFilter.ACTOR_ATTRIBUTE, ACTOR)
                .requestAttr(SessionAuthFilter.SESSION_ID_ATTRIBUTE, UUID.randomUUID());
    }

    private AppUser sampleUser() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new AppUser(ACTOR.userId(), "a@b.com", "Deepon", "{argon2}unused", now, now);
    }

    @Test
    void registerReturns201AndSetsCookie() throws Exception {
        when(authService.register(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new AuthService.AuthResult(sampleUser(), "raw-token", Instant.now().plusSeconds(3600)));
        when(cookieSupport.issue(anyString(), any(), any())).thenReturn("sid=raw-token; Path=/; HttpOnly");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"a-long-enough-password\",\"displayName\":\"Deepon\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Set-Cookie"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.email").value("a@b.com"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void registerWithTakenEmailReturns409() throws Exception {
        when(authService.register(anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new EmailTakenException());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"a-long-enough-password\",\"displayName\":\"Deepon\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    void loginSuccessReturns200AndSetsCookie() throws Exception {
        when(authService.login(anyString(), anyString(), any(), any(), isNull()))
                .thenReturn(new AuthService.AuthResult(sampleUser(), "raw-token", Instant.now().plusSeconds(3600)));
        when(cookieSupport.issue(anyString(), any(), any())).thenReturn("sid=raw-token; Path=/; HttpOnly");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"a-long-enough-password\"}"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Set-Cookie"));
    }

    @Test
    void loginWithBadCredentialsReturns401() throws Exception {
        when(authService.login(anyString(), anyString(), any(), any(), isNull()))
                .thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void loginWhileLockedReturns423() throws Exception {
        when(authService.login(anyString(), anyString(), any(), any(), isNull()))
                .thenThrow(new AccountLockedException());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"whatever\"}"))
                .andExpect(status().is(423))
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    void loginRateLimitedReturns429WithRetryAfter() throws Exception {
        when(authService.login(anyString(), anyString(), any(), any(), isNull()))
                .thenThrow(new RateLimitedException(42));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"whatever\"}"))
                .andExpect(status().is(429))
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void logoutReturns204AndClearsCookieEvenWithoutASession() throws Exception {
        when(cookieSupport.clear()).thenReturn("sid=; Path=/; Max-Age=0");

        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isNoContent())
                .andExpect(header().exists("Set-Cookie"));
    }

    @Test
    void meWithoutASessionReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_INVALID"));
    }

    @Test
    void meWithASessionReturns200() throws Exception {
        when(userService.getActiveById(ACTOR.userId())).thenReturn(sampleUser());

        mockMvc.perform(asUser(get("/api/v1/auth/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("a@b.com"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void listSessionsRequiresAnActor() throws Exception {
        mockMvc.perform(get("/api/v1/auth/sessions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listSessionsWithASessionReturns200() throws Exception {
        when(sessionService.listActive(ACTOR.userId())).thenReturn(java.util.List.of());

        mockMvc.perform(asUser(get("/api/v1/auth/sessions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
