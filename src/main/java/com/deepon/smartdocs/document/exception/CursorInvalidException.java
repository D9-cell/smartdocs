package com.deepon.smartdocs.document.exception;

/** Unparsable keyset pagination cursor — always 400, never a stack trace (design doc section 7.2). */
public class CursorInvalidException extends RuntimeException {

    public CursorInvalidException(String detail) {
        super(detail);
    }
}
