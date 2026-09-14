package com.deepon.statecore.error;

/**
 * Carries a full snapshot of the current row so the web layer can echo
 * {@code currentContent} in the 412 body and save the client a round trip.
 * The web layer decides whether to actually include the content (it is
 * omitted above a size threshold — see the API design doc, section 6.5).
 */
public class VersionMismatchException extends RuntimeException {

    private final long expectedVersion;
    private final long currentVersion;
    private final String currentContent;
    private final String currentContentHash;
    private final int currentContentSizeBytes;

    public VersionMismatchException(long expectedVersion, long currentVersion, String currentContent,
                                     String currentContentHash, int currentContentSizeBytes) {
        super("Version mismatch: expected " + expectedVersion + " but current is " + currentVersion);
        this.expectedVersion = expectedVersion;
        this.currentVersion = currentVersion;
        this.currentContent = currentContent;
        this.currentContentHash = currentContentHash;
        this.currentContentSizeBytes = currentContentSizeBytes;
    }

    public long getExpectedVersion() {
        return expectedVersion;
    }

    public long getCurrentVersion() {
        return currentVersion;
    }

    public String getCurrentContent() {
        return currentContent;
    }

    public String getCurrentContentHash() {
        return currentContentHash;
    }

    public int getCurrentContentSizeBytes() {
        return currentContentSizeBytes;
    }
}
