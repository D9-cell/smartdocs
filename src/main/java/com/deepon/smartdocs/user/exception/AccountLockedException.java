package com.deepon.smartdocs.user.exception;

/** Thrown while {@code locked_until} is still in the future. Correct credentials do not clear a lockout early. */
public class AccountLockedException extends RuntimeException {

    public AccountLockedException() {
        super("This account is temporarily locked after too many failed attempts.");
    }
}
