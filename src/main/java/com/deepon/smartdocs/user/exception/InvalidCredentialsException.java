package com.deepon.smartdocs.user.exception;

/**
 * The one exception thrown for every login failure the caller should not be
 * able to tell apart: unknown email, wrong password, and a disabled account
 * all resolve to this (design doc section 5.2 — never distinguish these).
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password.");
    }
}
