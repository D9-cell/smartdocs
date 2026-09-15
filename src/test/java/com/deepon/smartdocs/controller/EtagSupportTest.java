package com.deepon.smartdocs.controller;

import com.deepon.smartdocs.exception.MalformedIfMatchException;
import com.deepon.smartdocs.exception.PreconditionRequiredException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EtagSupportTest {

    private final EtagSupport etagSupport = new EtagSupport();

    @Test
    void parsesAQuotedInteger() {
        assertThat(etagSupport.requireVersion("\"7\"")).isEqualTo(7L);
    }

    @Test
    void toleratesSurroundingWhitespace() {
        assertThat(etagSupport.requireVersion("  \"42\"  ")).isEqualTo(42L);
    }

    @Test
    void nullHeaderIsPreconditionRequired() {
        assertThatThrownBy(() -> etagSupport.requireVersion(null))
                .isInstanceOf(PreconditionRequiredException.class);
    }

    @Test
    void blankHeaderIsPreconditionRequired() {
        assertThatThrownBy(() -> etagSupport.requireVersion("   "))
                .isInstanceOf(PreconditionRequiredException.class);
    }

    @Test
    void unquotedIntegerIsMalformed() {
        assertThatThrownBy(() -> etagSupport.requireVersion("7"))
                .isInstanceOf(MalformedIfMatchException.class);
    }

    @Test
    void nonNumericQuotedValueIsMalformed() {
        assertThatThrownBy(() -> etagSupport.requireVersion("\"abc\""))
                .isInstanceOf(MalformedIfMatchException.class);
    }

    @Test
    void wildcardIsMalformed() {
        assertThatThrownBy(() -> etagSupport.requireVersion("*"))
                .isInstanceOf(MalformedIfMatchException.class);
    }

    @Test
    void formatWrapsVersionInQuotes() {
        assertThat(etagSupport.format(8L)).isEqualTo("\"8\"");
    }
}
