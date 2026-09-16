package com.deepon.smartdocs.websocket.message;

/** design doc section 6.5. */
public enum ServerMessageType {
    SNAPSHOT("doc.snapshot"),
    IN_SYNC("doc.in_sync"),
    APPLIED("doc.applied"),
    CHANGED("doc.changed"),
    RENAMED("doc.renamed"),
    DELETED("doc.deleted"),
    ERROR("error"),
    PONG("pong");

    private final String wireValue;

    ServerMessageType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
