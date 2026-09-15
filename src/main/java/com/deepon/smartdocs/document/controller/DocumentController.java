package com.deepon.smartdocs.document.controller;

import com.deepon.smartdocs.common.web.EtagSupport;
import com.deepon.smartdocs.document.entity.Document;
import com.deepon.smartdocs.document.service.DocumentService;
import com.deepon.smartdocs.document.dto.ContentUpdateResponse;
import com.deepon.smartdocs.document.dto.CreateDocumentRequest;
import com.deepon.smartdocs.document.dto.DocumentMetaResponse;
import com.deepon.smartdocs.document.dto.DocumentResponse;
import com.deepon.smartdocs.document.dto.DocumentSummaryResponse;
import com.deepon.smartdocs.document.dto.RenameDocumentRequest;
import com.deepon.smartdocs.document.dto.UpdateContentRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Thin by design (section 5.2): parses {@code If-Match}, delegates to
 * {@link DocumentService}, maps the result to a DTO, sets {@code ETag}. No
 * business rule lives here — that keeps this class safe to leave alone when
 * later stages swap HTTP for WebSocket on some flows (section 3.3).
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private static final String IF_MATCH = "If-Match";
    private static final String IF_NONE_MATCH = "If-None-Match";

    private final DocumentService documentService;
    private final EtagSupport etagSupport;

    public DocumentController(DocumentService documentService, EtagSupport etagSupport) {
        this.documentService = documentService;
        this.etagSupport = etagSupport;
    }

    @PostMapping
    public ResponseEntity<DocumentResponse> create(@RequestBody(required = false) CreateDocumentRequest request) {
        CreateDocumentRequest body = request == null ? new CreateDocumentRequest(null, null) : request;
        Document created = documentService.create(body.title(), body.content());
        return ResponseEntity
                .created(URI.create("/api/v1/documents/" + created.getId()))
                .eTag(etagSupport.format(created.getVersion()))
                .body(DocumentResponse.from(created));
    }

    @GetMapping
    public ResponseEntity<List<DocumentSummaryResponse>> list(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        List<DocumentSummaryResponse> summaries = documentService.list(limit, offset).stream()
                .map(DocumentSummaryResponse::from)
                .toList();
        return ResponseEntity.ok(summaries);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentResponse> get(@PathVariable UUID id,
                                                 @RequestHeader(value = IF_NONE_MATCH, required = false) String ifNoneMatch) {
        Document document = documentService.get(id);
        String etag = etagSupport.format(document.getVersion());

        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(CacheControl.noStore())
                    .build();
        }

        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noStore())
                .body(DocumentResponse.from(document));
    }

    @PutMapping("/{id}/content")
    public ResponseEntity<ContentUpdateResponse> updateContent(
            @PathVariable UUID id,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
            @RequestBody UpdateContentRequest request) {
        long expectedVersion = etagSupport.requireVersion(ifMatch);
        Document updated = documentService.updateContent(id, expectedVersion, request.content(), request.clientHash());
        return ResponseEntity.ok()
                .eTag(etagSupport.format(updated.getVersion()))
                .body(ContentUpdateResponse.from(updated));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<DocumentMetaResponse> rename(
            @PathVariable UUID id,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
            @RequestBody RenameDocumentRequest request) {
        long expectedVersion = etagSupport.requireVersion(ifMatch);
        Document updated = documentService.rename(id, expectedVersion, request.title());
        return ResponseEntity.ok()
                .eTag(etagSupport.format(updated.getVersion()))
                .body(DocumentMetaResponse.from(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch) {
        long expectedVersion = etagSupport.requireVersion(ifMatch);
        documentService.softDelete(id, expectedVersion);
        return ResponseEntity.noContent().build();
    }
}
