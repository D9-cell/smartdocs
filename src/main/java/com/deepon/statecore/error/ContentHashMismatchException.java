package com.deepon.statecore.error;

/** {@code clientHash} disagreed with the server-computed hash of the same body. */
public class ContentHashMismatchException extends RuntimeException {

    private final String clientHash;
    private final String serverHash;

    public ContentHashMismatchException(String clientHash, String serverHash) {
        super("clientHash " + clientHash + " does not match computed hash " + serverHash);
        this.clientHash = clientHash;
        this.serverHash = serverHash;
    }

    public String getClientHash() {
        return clientHash;
    }

    public String getServerHash() {
        return serverHash;
    }
}
