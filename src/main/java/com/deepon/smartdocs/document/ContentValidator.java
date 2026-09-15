package com.deepon.smartdocs.document;

import com.deepon.smartdocs.document.exception.ContentTooLargeException;
import com.deepon.smartdocs.document.exception.InvalidContentException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Guards the byte-level identity the design depends on: whatever bytes the
 * server accepts are the bytes it stores and echoes back, unchanged. Every
 * rejection here exists so that guarantee never has to bend at write time.
 */
@Component
public class ContentValidator {

    public static final int DEFAULT_MAX_TITLE_LENGTH = 255;
    public static final String DEFAULT_TITLE = "Untitled";

    private final int maxContentBytes;

    public ContentValidator(@Value("${smartdocs.content.max-bytes:1048576}") int maxContentBytes) {
        this.maxContentBytes = maxContentBytes;
    }

    /**
     * @throws InvalidContentException if content is {@code null}, contains a
     *         NUL code point, a carriage return, or an unpaired UTF-16 surrogate.
     * @throws ContentTooLargeException if the UTF-8 encoding exceeds the configured limit.
     */
    public void validateContent(String content) {
        if (content == null) {
            throw new InvalidContentException("CONTENT_REQUIRED", "content", "content must not be null");
        }

        int sizeBytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (sizeBytes > maxContentBytes) {
            throw new ContentTooLargeException(sizeBytes, maxContentBytes);
        }

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\0') {
                throw new InvalidContentException("INVALID_CONTENT_NUL", "content",
                        "content contains a NUL code point at index " + i);
            }
            if (c == '\r') {
                throw new InvalidContentException("INVALID_CONTENT_CR", "content",
                        "content contains a carriage return at index " + i);
            }
            if (Character.isHighSurrogate(c)) {
                boolean hasLowPair = i + 1 < content.length() && Character.isLowSurrogate(content.charAt(i + 1));
                if (!hasLowPair) {
                    throw new InvalidContentException("INVALID_CONTENT_SURROGATE", "content",
                            "content contains an unpaired high surrogate at index " + i);
                }
                i++; // consume the validated low surrogate too
            } else if (Character.isLowSurrogate(c)) {
                throw new InvalidContentException("INVALID_CONTENT_SURROGATE", "content",
                        "content contains an unpaired low surrogate at index " + i);
            }
        }
    }

    /**
     * Trims, applies the {@code Untitled} default for a null or blank title,
     * and enforces the 255-character limit. Returns the normalized title.
     *
     * @throws InvalidContentException {@code TITLE_TOO_LONG} if the trimmed title exceeds 255 characters.
     */
    public String normalizeAndValidateTitle(String rawTitle) {
        String trimmed = rawTitle == null ? "" : rawTitle.trim();
        String title = trimmed.isEmpty() ? DEFAULT_TITLE : trimmed;
        if (title.length() > DEFAULT_MAX_TITLE_LENGTH) {
            throw new InvalidContentException("TITLE_TOO_LONG", "title",
                    "title exceeds " + DEFAULT_MAX_TITLE_LENGTH + " characters");
        }
        return title;
    }
}
