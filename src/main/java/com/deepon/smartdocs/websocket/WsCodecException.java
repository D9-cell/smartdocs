package com.deepon.smartdocs.websocket;

/** Malformed envelope or payload — the handler catches this and answers with an {@code error} frame carrying {@code MALFORMED}. */
public class WsCodecException extends RuntimeException {

    public WsCodecException(String message) {
        super(message);
    }

    public WsCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
