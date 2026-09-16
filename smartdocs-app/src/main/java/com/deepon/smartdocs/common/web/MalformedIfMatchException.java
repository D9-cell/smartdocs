package com.deepon.smartdocs.common.web;

/** Thrown when {@code If-Match} is present but is not a quoted integer, e.g. {@code "7"}. */
public class MalformedIfMatchException extends RuntimeException {

    public MalformedIfMatchException(String rawValue) {
        super("If-Match header is not a quoted integer: " + rawValue);
    }
}
