package com.deepon.smartdocs.websocket.dto;

import com.deepon.smartdocs.websocket.service.WsTicketService;

import java.time.Instant;

public record WsTicketResponse(String ticket, Instant expiresAt, String wsUrl) {

    public static WsTicketResponse from(WsTicketService.Issued issued) {
        return new WsTicketResponse(issued.rawToken(), issued.expiresAt(), "/ws");
    }
}
