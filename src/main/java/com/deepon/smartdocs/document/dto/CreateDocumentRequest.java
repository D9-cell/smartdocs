package com.deepon.smartdocs.document.dto;

/** {@code title} defaults to {@code Untitled} when null/blank; {@code content} defaults to {@code ""} when null. */
public record CreateDocumentRequest(String title, String content) {
}
