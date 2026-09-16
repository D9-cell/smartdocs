package com.deepon.smartdocs.user.exception;

/** {@code DELETE /auth/sessions/{id}} for an id that doesn't exist, or belongs to someone else. */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException() {
        super("No such session.");
    }
}
