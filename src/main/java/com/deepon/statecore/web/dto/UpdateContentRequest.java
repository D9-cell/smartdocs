package com.deepon.statecore.web.dto;

/**
 * {@code clientHash} is optional; when present and it disagrees with the
 * server-computed hash of {@code content}, the request is rejected with
 * {@code 400 CONTENT_HASH_MISMATCH} (design doc section 6.4).
 */
public record UpdateContentRequest(String content, String clientHash) {
}
