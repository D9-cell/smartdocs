package com.deepon.smartdocs.revision.controller;

import com.deepon.smartdocs.revision.entity.DocumentRevision;
import com.deepon.smartdocs.revision.dto.RevisionResponse;
import com.deepon.smartdocs.revision.dto.RevisionSummaryResponse;
import com.deepon.smartdocs.revision.service.RevisionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Thin by design, same as {@code DocumentController} (section 5.2). */
@RestController
@RequestMapping("/api/v1/documents/{documentId}/revisions")
public class RevisionController {

    private final RevisionService revisionService;

    public RevisionController(RevisionService revisionService) {
        this.revisionService = revisionService;
    }

    @GetMapping
    public ResponseEntity<List<RevisionSummaryResponse>> list(@PathVariable UUID documentId) {
        List<RevisionSummaryResponse> revisions = revisionService.listRevisions(documentId).stream()
                .map(RevisionSummaryResponse::from)
                .toList();
        return ResponseEntity.ok(revisions);
    }

    @GetMapping("/{version}")
    public ResponseEntity<RevisionResponse> get(@PathVariable UUID documentId, @PathVariable long version) {
        DocumentRevision revision = revisionService.getRevision(documentId, version);
        return ResponseEntity.ok(RevisionResponse.from(revision));
    }
}
