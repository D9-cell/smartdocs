package com.deepon.smartdocs.document.dto;

import java.util.List;

/** Keyset pagination envelope (design doc section 7.2). {@code nextCursor} is {@code null} on the last page. */
public record ListDocumentsResponse(List<DocumentSummaryResponse> items, String nextCursor) {
}
