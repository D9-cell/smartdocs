package com.deepon.smartdocs.exception;

/** Content exceeds the configured maximum, in UTF-8 bytes. */
public class ContentTooLargeException extends RuntimeException {

    private final long sizeBytes;
    private final long maxBytes;

    public ContentTooLargeException(long sizeBytes, long maxBytes) {
        super("Content size " + sizeBytes + " bytes exceeds the limit of " + maxBytes + " bytes.");
        this.sizeBytes = sizeBytes;
        this.maxBytes = maxBytes;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public long getMaxBytes() {
        return maxBytes;
    }
}
