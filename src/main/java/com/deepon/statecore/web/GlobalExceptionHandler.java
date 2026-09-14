package com.deepon.statecore.web;

import com.deepon.statecore.config.RequestIdFilter;
import com.deepon.statecore.error.ContentHashMismatchException;
import com.deepon.statecore.error.ContentTooLargeException;
import com.deepon.statecore.error.DocumentNotFoundException;
import com.deepon.statecore.error.InvalidContentException;
import com.deepon.statecore.error.MalformedIfMatchException;
import com.deepon.statecore.error.PreconditionRequiredException;
import com.deepon.statecore.error.RequestBodyTooLargeIOException;
import com.deepon.statecore.error.VersionMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;

/**
 * One {@code @ExceptionHandler} per domain exception type, each producing
 * RFC 9457 {@code application/problem+json} with a stable machine-readable
 * {@code code} field (design doc section 5.2, 6.7). Clients branch on
 * {@code code}, never on {@code detail} text — {@code detail} is free to
 * change wording without breaking a client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final URI PROBLEM_BASE = URI.create("https://statecore.dev/problems/");

    /** Above this, {@code currentContent} is omitted from a 412 body and {@code currentContentTruncated: true} is set instead. */
    private static final int MAX_ECHOED_CONTENT_BYTES = 256 * 1024;

    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(DocumentNotFoundException ex) {
        ProblemDetail pd = problem(HttpStatus.NOT_FOUND, "document-not-found", "Document not found",
                "No document exists with id " + ex.getDocumentId() + ", or it has been deleted.");
        pd.setProperty("code", "DOCUMENT_NOT_FOUND");
        return respond(HttpStatus.NOT_FOUND, pd);
    }

    @ExceptionHandler(VersionMismatchException.class)
    public ResponseEntity<ProblemDetail> handleVersionMismatch(VersionMismatchException ex) {
        ProblemDetail pd = problem(HttpStatus.PRECONDITION_FAILED, "version-mismatch", "Version mismatch",
                "Document was modified after the version you loaded.");
        pd.setProperty("code", "VERSION_MISMATCH");
        pd.setProperty("expectedVersion", ex.getExpectedVersion());
        pd.setProperty("currentVersion", ex.getCurrentVersion());
        pd.setProperty("currentContentHash", ex.getCurrentContentHash());
        if (ex.getCurrentContentSizeBytes() > MAX_ECHOED_CONTENT_BYTES) {
            pd.setProperty("currentContentTruncated", true);
        } else {
            pd.setProperty("currentContent", ex.getCurrentContent());
        }
        return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                .eTag("\"" + ex.getCurrentVersion() + "\"")
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }

    @ExceptionHandler(PreconditionRequiredException.class)
    public ResponseEntity<ProblemDetail> handlePreconditionRequired(PreconditionRequiredException ex) {
        ProblemDetail pd = problem(HttpStatus.PRECONDITION_REQUIRED, "precondition-required", "Precondition required", ex.getMessage());
        pd.setProperty("code", "PRECONDITION_REQUIRED");
        return respond(HttpStatus.PRECONDITION_REQUIRED, pd);
    }

    @ExceptionHandler(MalformedIfMatchException.class)
    public ResponseEntity<ProblemDetail> handleMalformedIfMatch(MalformedIfMatchException ex) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "malformed-if-match", "Malformed If-Match header", ex.getMessage());
        pd.setProperty("code", "MALFORMED_IF_MATCH");
        return respond(HttpStatus.BAD_REQUEST, pd);
    }

    @ExceptionHandler(ContentTooLargeException.class)
    public ResponseEntity<ProblemDetail> handleContentTooLarge(ContentTooLargeException ex) {
        ProblemDetail pd = problem(HttpStatus.PAYLOAD_TOO_LARGE, "content-too-large", "Content too large", ex.getMessage());
        pd.setProperty("code", "CONTENT_TOO_LARGE");
        pd.setProperty("maxBytes", ex.getMaxBytes());
        return respond(HttpStatus.PAYLOAD_TOO_LARGE, pd);
    }

    @ExceptionHandler(InvalidContentException.class)
    public ResponseEntity<ProblemDetail> handleInvalidContent(InvalidContentException ex) {
        ProblemDetail pd = problem(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-content", "Invalid content", ex.getMessage());
        pd.setProperty("code", ex.getCode());
        pd.setProperty("field", ex.getField());
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, pd);
    }

    @ExceptionHandler(ContentHashMismatchException.class)
    public ResponseEntity<ProblemDetail> handleContentHashMismatch(ContentHashMismatchException ex) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "content-hash-mismatch", "Content hash mismatch", ex.getMessage());
        pd.setProperty("code", "CONTENT_HASH_MISMATCH");
        return respond(HttpStatus.BAD_REQUEST, pd);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "invalid-document-id", "Invalid path parameter",
                "Path parameter '" + ex.getName() + "' is not a valid " +
                        (ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "value") + ".");
        pd.setProperty("code", "INVALID_DOCUMENT_ID");
        return respond(HttpStatus.BAD_REQUEST, pd);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException ex) {
        if (unwrapTooLarge(ex) != null) {
            ProblemDetail pd = problem(HttpStatus.PAYLOAD_TOO_LARGE, "content-too-large", "Content too large",
                    unwrapTooLarge(ex).getMessage());
            pd.setProperty("code", "CONTENT_TOO_LARGE");
            return respond(HttpStatus.PAYLOAD_TOO_LARGE, pd);
        }
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "malformed-json", "Malformed request body",
                "The request body could not be parsed as JSON.");
        pd.setProperty("code", "MALFORMED_JSON");
        return respond(HttpStatus.BAD_REQUEST, pd);
    }

    private RequestBodyTooLargeIOException unwrapTooLarge(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof RequestBodyTooLargeIOException tooLarge) {
                return tooLarge;
            }
            cause = cause.getCause();
        }
        return null;
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        ProblemDetail pd = problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-media-type", "Unsupported media type",
                "Content-Type must be application/json.");
        pd.setProperty("code", "UNSUPPORTED_MEDIA_TYPE");
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, pd);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        ProblemDetail pd = problem(HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed", "Method not allowed", ex.getMessage());
        pd.setProperty("code", "METHOD_NOT_ALLOWED");
        return respond(HttpStatus.METHOD_NOT_ALLOWED, pd);
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class, QueryTimeoutException.class})
    public ResponseEntity<ProblemDetail> handleDatabaseUnavailable(Exception ex) {
        String correlationId = MDC.get(RequestIdFilter.MDC_KEY);
        log.error("Database unavailable, correlationId={}", correlationId, ex);
        ProblemDetail pd = problem(HttpStatus.SERVICE_UNAVAILABLE, "database-unavailable", "Database unavailable",
                "The database is temporarily unavailable. Correlation id: " + correlationId);
        pd.setProperty("code", "DATABASE_UNAVAILABLE");
        return respond(HttpStatus.SERVICE_UNAVAILABLE, pd);
    }

    @ExceptionHandler({Exception.class, HttpMessageNotWritableException.class})
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        String correlationId = MDC.get(RequestIdFilter.MDC_KEY);
        log.error("Unhandled exception, correlationId={}", correlationId, ex);
        ProblemDetail pd = problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error",
                "An unexpected error occurred. Correlation id: " + correlationId);
        pd.setProperty("code", "INTERNAL_ERROR");
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, pd);
    }

    private ProblemDetail problem(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(PROBLEM_BASE.resolve(slug));
        pd.setTitle(title);
        return pd;
    }

    private ResponseEntity<ProblemDetail> respond(HttpStatus status, ProblemDetail pd) {
        return ResponseEntity.status(status)
                .headers(h -> h.setContentType(MediaType.APPLICATION_PROBLEM_JSON))
                .body(pd);
    }
}
