package com.deepon.smartdocs.websocket.controller;

import com.deepon.smartdocs.common.Actor;
import com.deepon.smartdocs.websocket.dto.WsTicketResponse;
import com.deepon.smartdocs.websocket.service.WsTicketService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin by design, same rule as {@code AuthController}/{@code DocumentController}:
 * HTTP shape only, no business rules. {@code Actor} is injected the normal
 * way — this endpoint runs behind the same cookie-session auth as every
 * other authenticated REST call (design doc section 6.1); the ticket it
 * issues is a *separate*, short-lived credential for the handshake that
 * follows, not a replacement for the session cookie.
 */
@RestController
@RequestMapping("/api/v1/ws-tickets")
public class WsTicketController {

    private final WsTicketService wsTicketService;

    public WsTicketController(WsTicketService wsTicketService) {
        this.wsTicketService = wsTicketService;
    }

    @PostMapping
    public ResponseEntity<WsTicketResponse> issue(Actor actor, HttpServletRequest httpRequest) {
        WsTicketService.Issued issued = wsTicketService.issue(actor.userId(), httpRequest.getRemoteAddr());
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .body(WsTicketResponse.from(issued));
    }
}
