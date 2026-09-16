package com.deepon.smartdocs.document.exception;

/**
 * A field-level content or title violation. {@code code} is one of the
 * 422 codes in the error catalogue: {@code INVALID_CONTENT_NUL},
 * {@code INVALID_CONTENT_CR}, {@code INVALID_CONTENT_SURROGATE},
 * {@code TITLE_TOO_LONG}.
 */
public class InvalidContentException extends RuntimeException {

    private final String code;
    private final String field;

    public InvalidContentException(String code, String field, String detail) {
        super(detail);
        this.code = code;
        this.field = field;
    }

    public String getCode() {
        return code;
    }

    public String getField() {
        return field;
    }
}
