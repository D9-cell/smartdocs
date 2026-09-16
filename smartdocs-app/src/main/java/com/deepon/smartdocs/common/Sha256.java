package com.deepon.smartdocs.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stateless SHA-256 hex helper shared by session-token hashing and IP hashing. */
public final class Sha256 {

    private static final HexFormat HEX = HexFormat.of();

    private Sha256() {
    }

    public static String hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every standard JVM.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
