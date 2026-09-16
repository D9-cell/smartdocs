package com.deepon.smartdocs.revision.service.impl;

import com.deepon.smartdocs.common.Actor;
import com.deepon.smartdocs.document.exception.DocumentNotFoundException;
import com.deepon.smartdocs.document.repository.DocumentRepository;
import com.deepon.smartdocs.revision.entity.DocumentRevision;
import com.deepon.smartdocs.revision.repository.DocumentRevisionRepository;
import com.deepon.smartdocs.revision.repository.RevisionSummaryProjection;
import com.deepon.smartdocs.revision.service.RevisionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class RevisionServiceImpl implements RevisionService {

    private static final String ACTOR_TYPE_HUMAN = "HUMAN";

    private final DocumentRevisionRepository revisionRepository;
    private final DocumentRepository documentRepository;

    public RevisionServiceImpl(DocumentRevisionRepository revisionRepository, DocumentRepository documentRepository) {
        this.revisionRepository = revisionRepository;
        this.documentRepository = documentRepository;
    }

    @Override
    @Transactional
    public void recordRevision(UUID documentId, long version, String content, String contentHash,
                                int contentSizeBytes, String actorId, Instant createdAt,
                                Long baseVersion, String source, UUID sessionId) {
        revisionRepository.save(new DocumentRevision(UUID.randomUUID(), documentId, version,
                content, contentHash, contentSizeBytes, actorId, ACTOR_TYPE_HUMAN, createdAt,
                baseVersion, source, sessionId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RevisionSummaryProjection> listRevisions(Actor actor, UUID documentId) {
        requireDocumentOwned(actor, documentId); // 404s if missing, soft-deleted, or not this caller's document
        return revisionRepository.findSummariesByDocumentId(documentId);
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentRevision getRevision(Actor actor, UUID documentId, long version) {
        requireDocumentOwned(actor, documentId);
        return revisionRepository.findByDocumentIdAndVersion(documentId, version)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }

    private void requireDocumentOwned(Actor actor, UUID documentId) {
        if (documentRepository.findByIdAndOwnerIdAndDeletedAtIsNull(documentId, actor.userId()).isEmpty()) {
            throw new DocumentNotFoundException(documentId);
        }
    }
}
