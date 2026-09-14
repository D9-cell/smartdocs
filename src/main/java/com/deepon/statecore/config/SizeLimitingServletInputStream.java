package com.deepon.statecore.config;

import com.deepon.statecore.error.RequestBodyTooLargeIOException;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;

import java.io.IOException;

/**
 * Counts bytes as they are read and fails once the caller-supplied limit is
 * crossed, so a chunked request (no {@code Content-Length} to reject up
 * front) can't exhaust memory before the application-level check in
 * {@code ContentValidator} ever runs.
 */
final class SizeLimitingServletInputStream extends ServletInputStream {

    private final ServletInputStream delegate;
    private final long maxBytes;
    private long readCount;

    SizeLimitingServletInputStream(ServletInputStream delegate, long maxBytes) {
        this.delegate = delegate;
        this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
        int b = delegate.read();
        if (b != -1) {
            readCount++;
            checkLimit();
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = delegate.read(b, off, len);
        if (n > 0) {
            readCount += n;
            checkLimit();
        }
        return n;
    }

    private void checkLimit() throws IOException {
        if (readCount > maxBytes) {
            throw new RequestBodyTooLargeIOException(maxBytes);
        }
    }

    @Override
    public boolean isFinished() {
        return delegate.isFinished();
    }

    @Override
    public boolean isReady() {
        return delegate.isReady();
    }

    @Override
    public void setReadListener(ReadListener readListener) {
        delegate.setReadListener(readListener);
    }
}
