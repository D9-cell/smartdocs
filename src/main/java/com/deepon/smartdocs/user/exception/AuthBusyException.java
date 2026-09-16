package com.deepon.smartdocs.user.exception;

/** The Argon2id hashing semaphore timed out acquiring a permit (design doc section 6.4). */
public class AuthBusyException extends RuntimeException {

    public AuthBusyException() {
        super("Authentication service is busy. Try again shortly.");
    }
}
