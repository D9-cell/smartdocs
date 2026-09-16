package com.deepon.smartdocs.document.controller;

import com.deepon.smartdocs.common.Actor;
import com.deepon.smartdocs.common.exception.ValidationFailedException;
import com.deepon.smartdocs.common.exception.ValidationFailedException.FieldViolation;
import com.deepon.smartdocs.common.web.EtagSupport;
import com.deepon.smartdocs.document.entity.Document;
import com.deepon.smartdocs.document.service.DocumentService;
import com.deepon.smartdocs.document.dto.ContentUpdateResponse;
import com.deepon.smartdocs.document.dto.CreateDocumentRequest;
import com.deepon.smartdocs.document.dto.DocumentMetaResponse;
import com.deepon.smartdocs.document.dto.DocumentResponse;
import com.deepon.smartdocs.document.dto.DocumentSummaryResponse;
import com.deepon.smartdocs.document.dto.ListDocumentsResponse;
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
 * later stages swap HTTP for WebSocket on some flows (section 3.3). Every
 * method now takes an {@link Actor}, injected by {@link com.deepon.smartdocs.common.ActorArgumentResolver}
 * from the session cookie; a request with no valid session never reaches
 * the body of these methods (design doc section 4).
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private static final String IF_MATCH = "If-Match";
    private static final String IF_NONE_MATCH = "If-None-Match";
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;

    private final DocumentService documentService;
    private final EtagSupport etagSupport;

    public DocumentController(DocumentService documentService, EtagSupport etagSupport) {
        this.documentService = documentService;
        this.etagSupport = etagSupport;
    }

    @PostMapping
    public ResponseEntity<DocumentResponse> create(Actor actor, @RequestBody(required = false) CreateDocumentRequest request) {
        CreateDocumentRequest body = request == null ? new CreateDocumentRequest(null, null) : request;
        Document created = documentService.create(actor, body.title(), body.content());
        return ResponseEntity
                .created(URI.create("/api/v1/documents/" + created.getId()))
                .eTag(etagSupport.format(created.getVersion()))
                .body(DocumentResponse.from(created));
    }

    @GetMapping
    public ResponseEntity<ListDocumentsResponse> list(
            Actor actor,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestParam(required = false) String cursor) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ValidationFailedException(List.of(
                    new FieldViolation("limit", "limit must be between 1 and " + MAX_LIMIT)));
        }
        DocumentService.Page page = documentService.list(actor, limit, cursor);
        List<DocumentSummaryResponse> items = page.items().stream().map(DocumentSummaryResponse::from).toList();
        return ResponseEntity.ok(new ListDocumentsResponse(items, page.nextCursor()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentResponse> get(Actor actor, @PathVariable UUID id,
                                                 @RequestHeader(value = IF_NONE_MATCH, required = false) String ifNoneMatch) {
        Document document = documentService.get(actor, id);
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
            Actor actor,
            @PathVariable UUID id,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
            @RequestBody UpdateContentRequest request) {
        long expectedVersion = etagSupport.requireVersion(ifMatch);
        Document updated = documentService.updateContent(actor, id, expectedVersion, request.content(), request.clientHash());
        return ResponseEntity.ok()
                .eTag(etagSupport.format(updated.getVersion()))
                .body(ContentUpdateResponse.from(updated));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<DocumentMetaResponse> rename(
            Actor actor,
            @PathVariable UUID id,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
            @RequestBody RenameDocumentRequest request) {
        long expectedVersion = etagSupport.requireVersion(ifMatch);
        Document updated = documentService.rename(actor, id, expectedVersion, request.title());
        return ResponseEntity.ok()
                .eTag(etagSupport.format(updated.getVersion()))
                .body(DocumentMetaResponse.from(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            Actor actor,
            @PathVariable UUID id,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch) {
        long expectedVersion = etagSupport.requireVersion(ifMatch);
        documentService.softDelete(actor, id, expectedVersion);
        return ResponseEntity.noContent().build();
    }
}
