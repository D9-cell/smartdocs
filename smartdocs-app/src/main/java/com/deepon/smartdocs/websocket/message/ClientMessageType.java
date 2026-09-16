package com.deepon.smartdocs.websocket.message;

import java.util.Arrays;
import java.util.Optional;

/** design doc section 6.4. */
public enum ClientMessageType {
    SUBSCRIBE("doc.subscribe"),
    UNSUBSCRIBE("doc.unsubscribe"),
    UPDATE("doc.update"),
    PING("ping");

    private final String wireValue;

    ClientMessageType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<ClientMessageType> fromWireValue(String value) {
        return Arrays.stream(values()).filter(t -> t.wireValue.equals(value)).findFirst();
    }
}
