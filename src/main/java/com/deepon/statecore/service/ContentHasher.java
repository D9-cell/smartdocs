package com.deepon.statecore.service;

import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 over the UTF-8 bytes of the content, hex-encoded lowercase, 64
 * characters. Used for no-op save detection and for retry idempotency
 * (section 8.2: a retried save after a timeout can tell it already
 * succeeded by comparing hashes).
 */
@Component
public class ContentHasher {

    private static final HexFormat HEX = HexFormat.of();

    public String hash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every standard JVM.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public int utf8SizeBytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8).length;
    }
}
