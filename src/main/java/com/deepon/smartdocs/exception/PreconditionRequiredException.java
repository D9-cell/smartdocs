package com.deepon.smartdocs.exception;

/** Thrown when a mutating request has no {@code If-Match} header at all. */
public class PreconditionRequiredException extends RuntimeException {

    public PreconditionRequiredException() {
        super("If-Match header is required on this operation.");
    }
}
