package com.deepon.smartdocs.common.exception;

import java.util.List;

/** 400 {@code VALIDATION_FAILED}: every violated field reported at once, not just the first (design doc section 7.4). */
public class ValidationFailedException extends RuntimeException {

    public record FieldViolation(String field, String message) {
    }

    private final List<FieldViolation> violations;

    public ValidationFailedException(List<FieldViolation> violations) {
        super("Validation failed: " + violations.size() + " field(s)");
        this.violations = violations;
    }

    public List<FieldViolation> getViolations() {
        return violations;
    }
}
