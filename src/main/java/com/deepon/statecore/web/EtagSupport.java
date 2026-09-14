package com.deepon.statecore.web;

import com.deepon.statecore.error.MalformedIfMatchException;
import com.deepon.statecore.error.PreconditionRequiredException;
import org.springframework.stereotype.Component;

/**
 * The document's {@code version} doubles as its ETag, formatted as a quoted
 * integer, e.g. {@code "7"}. This class is the only place that parses or
 * formats that convention.
 */
@Component
public class EtagSupport {

    /**
     * @throws PreconditionRequiredException if {@code rawIfMatch} is null or blank.
     * @throws MalformedIfMatchException if it is present but not a quoted integer.
     */
    public long requireVersion(String rawIfMatch) {
        if (rawIfMatch == null || rawIfMatch.isBlank()) {
            throw new PreconditionRequiredException();
        }
        String trimmed = rawIfMatch.trim();
        if (trimmed.length() < 3 || trimmed.charAt(0) != '"' || trimmed.charAt(trimmed.length() - 1) != '"') {
            throw new MalformedIfMatchException(rawIfMatch);
        }
        String digits = trimmed.substring(1, trimmed.length() - 1);
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            throw new MalformedIfMatchException(rawIfMatch);
        }
    }

    public String format(long version) {
        return "\"" + version + "\"";
    }
}
