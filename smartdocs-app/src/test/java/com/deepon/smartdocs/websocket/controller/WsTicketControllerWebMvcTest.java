package com.deepon.smartdocs.websocket.controller;

import com.deepon.smartdocs.common.Actor;
import com.deepon.smartdocs.common.ActorArgumentResolver;
import com.deepon.smartdocs.config.WebConfig;
import com.deepon.smartdocs.security.CookieSupport;
import com.deepon.smartdocs.security.SessionAuthFilter;
import com.deepon.smartdocs.user.exception.RateLimitedException;
import com.deepon.smartdocs.user.service.SessionService;
import com.deepon.smartdocs.websocket.service.WsTicketService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Design doc build order step 2, section 6.1: the ticket-issuance endpoint's status codes against a mocked service. */
@WebMvcTest(WsTicketController.class)
@Import({WebConfig.class, ActorArgumentResolver.class, SimpleMeterRegistry.class})
class WsTicketControllerWebMvcTest {

    private static final Actor ACTOR = Actor.human(UUID.fromString("33333333-3333-3333-3333-333333333333"));

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WsTicketService wsTicketService;

    // SessionAuthFilter is auto-detected as a Filter bean in this slice and
    // needs its own dependencies satisfied even though WsTicketController
    // never touches them directly (same reasoning as DocumentControllerWebMvcTest).
    @MockBean
    private SessionService sessionService;

    @MockBean
    private CookieSupport cookieSupport;

    private MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder builder) {
        return builder.requestAttr(SessionAuthFilter.ACTOR_ATTRIBUTE, ACTOR)
                .requestAttr(SessionAuthFilter.SESSION_ID_ATTRIBUTE, UUID.randomUUID());
    }

    @Test
    void issueWithoutASessionReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/ws-tickets"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_INVALID"));
    }

    @Test
    void issueWithASessionReturns201WithTicketAndWsUrl() throws Exception {
        when(wsTicketService.issue(any(), any()))
                .thenReturn(new WsTicketService.Issued("raw-ticket", Instant.parse("2026-01-01T00:00:30Z")));

        mockMvc.perform(asUser(post("/api/v1/ws-tickets")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.ticket").value("raw-ticket"))
                .andExpect(jsonPath("$.wsUrl").value("/ws"));
    }

    @Test
    void issueRateLimitedReturns429WithRetryAfter() throws Exception {
        when(wsTicketService.issue(any(), anyString())).thenThrow(new RateLimitedException(17));

        mockMvc.perform(asUser(post("/api/v1/ws-tickets")))
                .andExpect(status().is(429))
                .andExpect(header().string("Retry-After", "17"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }
}
