package com.deepon.statecore.service;

import com.deepon.statecore.domain.Document;
import com.deepon.statecore.domain.DocumentRepository;
import com.deepon.statecore.domain.DocumentRevision;
import com.deepon.statecore.domain.DocumentRevisionRepository;
import com.deepon.statecore.domain.DocumentSummaryProjection;
import com.deepon.statecore.domain.RevisionSummaryProjection;
import com.deepon.statecore.error.ContentHashMismatchException;
import com.deepon.statecore.error.DocumentNotFoundException;
import com.deepon.statecore.error.VersionMismatchException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The one place business rules live. Controllers never see an entity;
 * services never see an {@code HttpServletRequest}. See design doc section 3.3.
 */
@Service
public class DocumentService {

    /** Stage 0 has no accounts. Every write is attributed to this actor. See design doc section 2. */
    public static final String ANONYMOUS_ACTOR = "anonymous";
    private static final String ACTOR_TYPE_HUMAN = "HUMAN";

    private final DocumentRepository documentRepository;
    private final DocumentRevisionRepository revisionRepository;
    private final ContentValidator contentValidator;
    private final ContentHasher contentHasher;
    private final Clock clock;

    private final Counter saveTotal;
    private final Counter saveConflictTotal;
    private final Counter saveNoopTotal;

    public DocumentService(DocumentRepository documentRepository,
                            DocumentRevisionRepository revisionRepository,
                            ContentValidator contentValidator,
                            ContentHasher contentHasher,
                            Clock clock,
                            MeterRegistry meterRegistry) {
        this.documentRepository = documentRepository;
        this.revisionRepository = revisionRepository;
        this.contentValidator = contentValidator;
        this.contentHasher = contentHasher;
        this.clock = clock;
        this.saveTotal = meterRegistry.counter("document_save_total");
        this.saveConflictTotal = meterRegistry.counter("document_save_conflict_total");
        this.saveNoopTotal = meterRegistry.counter("document_save_noop_total");
    }

    @Transactional
    public Document create(String rawTitle, String rawContent) {
        String title = contentValidator.normalizeAndValidateTitle(rawTitle);
        String content = rawContent == null ? "" : rawContent;
        contentValidator.validateContent(content);

        String hash = contentHasher.hash(content);
        int sizeBytes = contentHasher.utf8SizeBytes(content);
        Instant now = clock.instant();

        Document document = new Document(UUID.randomUUID(), title, content, hash, sizeBytes,
                1L, ANONYMOUS_ACTOR, ANONYMOUS_ACTOR, now, now);
        documentRepository.save(document);
        revisionRepository.save(new DocumentRevision(UUID.randomUUID(), document.getId(), 1L,
                content, hash, sizeBytes, ANONYMOUS_ACTOR, ACTOR_TYPE_HUMAN, now));
        return document;
    }

    @Transactional(readOnly = true)
    public Document get(UUID id) {
        return documentRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<DocumentSummaryProjection> list(int limit, int offset) {
        int cappedLimit = Math.max(1, Math.min(limit, 200));
        Pageable pageable = PageRequest.of(offset / cappedLimit, cappedLimit, Sort.unsorted());
        // findSummaries already orders by updatedAt DESC; PageRequest here only paginates.
        return documentRepository.findSummaries(PageRequest.of(0, cappedLimit + offset)).stream()
                .skip(offset)
                .limit(cappedLimit)
                .toList();
    }

    /**
     * Steps exactly follow design doc section 5.2. Steps 4 and 6 look
     * redundant; they are not. Step 4 gives the client the current content in
     * the error body without a second round trip. Step 6 is the actual
     * correctness guarantee — the atomic conditional UPDATE.
     */
    @Transactional
    public Document updateContent(UUID id, long expectedVersion, String content, String clientHash) {
        saveTotal.increment();
        contentValidator.validateContent(content);
        String hash = contentHasher.hash(content);

        if (clientHash != null && !clientHash.equalsIgnoreCase(hash)) {
            throw new ContentHashMismatchException(clientHash, hash);
        }

        Document current = documentRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));

        if (current.getVersion() != expectedVersion) {
            saveConflictTotal.increment();
            throw versionMismatch(expectedVersion, current);
        }

        if (hash.equals(current.getContentHash())) {
            saveNoopTotal.increment();
            return current;
        }

        int sizeBytes = contentHasher.utf8SizeBytes(content);
        Instant now = clock.instant();
        int rows = documentRepository.updateContent(id, expectedVersion, content, hash, sizeBytes, ANONYMOUS_ACTOR, now);

        if (rows == 0) {
            saveConflictTotal.increment();
            throw resolveRaceAfterFailedWrite(id, expectedVersion);
        }

        Document updated = documentRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));
        revisionRepository.save(new DocumentRevision(UUID.randomUUID(), id, updated.getVersion(),
                content, hash, sizeBytes, ANONYMOUS_ACTOR, ACTOR_TYPE_HUMAN, now));
        return updated;
    }

    @Transactional
    public Document rename(UUID id, long expectedVersion, String rawTitle) {
        String title = contentValidator.normalizeAndValidateTitle(rawTitle);

        Document current = documentRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));
        if (current.getVersion() != expectedVersion) {
            throw versionMismatch(expectedVersion, current);
        }

        Instant now = clock.instant();
        int rows = documentRepository.updateTitle(id, expectedVersion, title, ANONYMOUS_ACTOR, now);
        if (rows == 0) {
            throw resolveRaceAfterFailedWrite(id, expectedVersion);
        }

        return documentRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));
    }

    @Transactional
    public void softDelete(UUID id, long expectedVersion) {
        Document current = documentRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));
        if (current.getVersion() != expectedVersion) {
            throw versionMismatch(expectedVersion, current);
        }

        Instant now = clock.instant();
        int rows = documentRepository.softDelete(id, expectedVersion, ANONYMOUS_ACTOR, now);
        if (rows == 0) {
            throw resolveRaceAfterFailedWrite(id, expectedVersion);
        }
    }

    @Transactional(readOnly = true)
    public List<RevisionSummaryProjection> listRevisions(UUID documentId) {
        get(documentId); // 404s if missing or soft-deleted; also confirms existence before listing history
        return revisionRepository.findSummariesByDocumentId(documentId);
    }

    @Transactional(readOnly = true)
    public DocumentRevision getRevision(UUID documentId, long version) {
        get(documentId);
        return revisionRepository.findByDocumentIdAndVersion(documentId, version)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }

    /**
     * Zero rows affected by the conditional UPDATE means one of three things:
     * the document is gone, soft-deleted, or someone else wrote first.
     * Distinguish by a follow-up read — never guess (design doc section 7.5).
     */
    private RuntimeException resolveRaceAfterFailedWrite(UUID id, long expectedVersion) {
        return documentRepository.findByIdAndDeletedAtIsNull(id)
                .<RuntimeException>map(reloaded -> versionMismatch(expectedVersion, reloaded))
                .orElseGet(() -> new DocumentNotFoundException(id));
    }

    private VersionMismatchException versionMismatch(long expectedVersion, Document current) {
        return new VersionMismatchException(expectedVersion, current.getVersion(), current.getContent(),
                current.getContentHash(), current.getContentSizeBytes());
    }
}
