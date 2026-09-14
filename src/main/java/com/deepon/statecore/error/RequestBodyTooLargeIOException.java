package com.deepon.statecore.error;

import java.io.IOException;

/**
 * Thrown by the counting input stream in {@code RequestSizeLimitingFilter}
 * when a chunked request body (no {@code Content-Length}) exceeds the
 * configured limit while being read. Spring wraps it as the cause of an
 * {@code HttpMessageNotReadableException}; {@code GlobalExceptionHandler}
 * unwraps it to tell "body too large" apart from "body malformed".
 */
public class RequestBodyTooLargeIOException extends IOException {

    public RequestBodyTooLargeIOException(long maxBytes) {
        super("Request body exceeds the limit of " + maxBytes + " bytes.");
    }
}
