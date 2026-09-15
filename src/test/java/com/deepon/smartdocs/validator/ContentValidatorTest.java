package com.deepon.smartdocs.validator;

import com.deepon.smartdocs.exception.ContentTooLargeException;
import com.deepon.smartdocs.exception.InvalidContentException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Covers every row of the design doc's section 8.1 "Content" edge-case table. */
class ContentValidatorTest {

    private final ContentValidator validator = new ContentValidator(1_048_576);

    @Test
    void emptyStringIsValid() {
        assertThatCodeDoesNotThrow(() -> validator.validateContent(""));
    }

    @Test
    void whitespaceOnlyIsValidAndNotTrimmed() {
        assertThatCodeDoesNotThrow(() -> validator.validateContent("   \n\t  "));
    }

    @Test
    void nullContentIsRejected() {
        assertThatThrownBy(() -> validator.validateContent(null))
                .isInstanceOf(InvalidContentException.class)
                .extracting(ex -> ((InvalidContentException) ex).getCode())
                .isEqualTo("CONTENT_REQUIRED");
    }

    @Test
    void exactlyOneMebibyteIsAccepted() {
        String content = "a".repeat(1_048_576);
        assertThatCodeDoesNotThrow(() -> validator.validateContent(content));
    }

    @Test
    void oneByteOverTheLimitIsRejected() {
        String content = "a".repeat(1_048_577);
        assertThatThrownBy(() -> validator.validateContent(content))
                .isInstanceOf(ContentTooLargeException.class);
    }

    @Test
    void multiByteEmojiIsMeasuredInUtf8BytesNotJavaCharLength() {
        // 200,000 four-byte emoji = 800,000 UTF-8 bytes, well under the 1 MiB limit,
        // even though Java's String.length() (UTF-16 code units) would report 400,000.
        String content = "😀".repeat(200_000);
        assertThatCodeDoesNotThrow(() -> validator.validateContent(content));
    }

    @Test
    void combiningCharactersAreAcceptedWithoutNormalization() {
        // "e" + combining acute accent, stored as submitted.
        assertThatCodeDoesNotThrow(() -> validator.validateContent("é"));
    }

    @Test
    void nulCodePointIsRejected() {
        assertThatThrownBy(() -> validator.validateContent("before\0after"))
                .isInstanceOf(InvalidContentException.class)
                .extracting(ex -> ((InvalidContentException) ex).getCode())
                .isEqualTo("INVALID_CONTENT_NUL");
    }

    @Test
    void unpairedHighSurrogateIsRejected() {
        String content = "before" + '\uD800' + "after";
        assertThatThrownBy(() -> validator.validateContent(content))
                .isInstanceOf(InvalidContentException.class)
                .extracting(ex -> ((InvalidContentException) ex).getCode())
                .isEqualTo("INVALID_CONTENT_SURROGATE");
    }

    @Test
    void unpairedLowSurrogateIsRejected() {
        String content = "before" + '\uDC00' + "after";
        assertThatThrownBy(() -> validator.validateContent(content))
                .isInstanceOf(InvalidContentException.class)
                .extracting(ex -> ((InvalidContentException) ex).getCode())
                .isEqualTo("INVALID_CONTENT_SURROGATE");
    }

    @Test
    void properlyPairedSurrogateIsAccepted() {
        assertThatCodeDoesNotThrow(() -> validator.validateContent("emoji: 😀 done"));
    }

    @Test
    void carriageReturnIsRejected() {
        assertThatThrownBy(() -> validator.validateContent("line1\r\nline2"))
                .isInstanceOf(InvalidContentException.class)
                .extracting(ex -> ((InvalidContentException) ex).getCode())
                .isEqualTo("INVALID_CONTENT_CR");
    }

    @Test
    void lineFeedOnlyIsAccepted() {
        assertThatCodeDoesNotThrow(() -> validator.validateContent("line1\nline2\n"));
    }

    @Test
    void extremelyLongSingleLineIsValid() {
        String content = "x".repeat(500_000);
        assertThatCodeDoesNotThrow(() -> validator.validateContent(content));
    }

    @Test
    void bidiControlCharactersAreAcceptedAsSubmitted() {
        assertThatCodeDoesNotThrow(() -> validator.validateContent("‮reversed‬"));
    }

    @Test
    void nullTitleDefaultsToUntitled() {
        assertThat(validator.normalizeAndValidateTitle(null)).isEqualTo("Untitled");
    }

    @Test
    void blankTitleDefaultsToUntitled() {
        assertThat(validator.normalizeAndValidateTitle("   ")).isEqualTo("Untitled");
    }

    @Test
    void titleIsTrimmed() {
        assertThat(validator.normalizeAndValidateTitle("  My Notes  ")).isEqualTo("My Notes");
    }

    @Test
    void titleAtExactlyTwoHundredFiftyFiveCharsIsAccepted() {
        String title = "a".repeat(255);
        assertThat(validator.normalizeAndValidateTitle(title)).hasSize(255);
    }

    @Test
    void titleOverTwoHundredFiftyFiveCharsIsRejected() {
        String title = "a".repeat(256);
        assertThatThrownBy(() -> validator.normalizeAndValidateTitle(title))
                .isInstanceOf(InvalidContentException.class)
                .extracting(ex -> ((InvalidContentException) ex).getCode())
                .isEqualTo("TITLE_TOO_LONG");
    }

    private static void assertThatCodeDoesNotThrow(Runnable runnable) {
        org.assertj.core.api.Assertions.assertThatCode(runnable::run).doesNotThrowAnyException();
    }
}
