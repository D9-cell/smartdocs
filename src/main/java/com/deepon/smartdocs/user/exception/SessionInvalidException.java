package com.deepon.smartdocs.user.exception;

/** Missing, unknown, expired, or revoked session cookie — one outcome for all four (design doc section 10.3). */
public class SessionInvalidException extends RuntimeException {

    public SessionInvalidException() {
        super("Session is missing, expired, or invalid.");
    }
}
