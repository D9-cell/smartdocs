package com.deepon.smartdocs.document.service.impl;

import com.deepon.smartdocs.common.Actor;
import com.deepon.smartdocs.document.ContentHasher;
import com.deepon.smartdocs.document.ContentValidator;
import com.deepon.smartdocs.document.CursorCodec;
import com.deepon.smartdocs.document.entity.Document;
import com.deepon.smartdocs.document.exception.ContentHashMismatchException;
import com.deepon.smartdocs.document.exception.DocumentLimitReachedException;
import com.deepon.smartdocs.document.exception.DocumentNotFoundException;
import com.deepon.smartdocs.document.exception.VersionMismatchException;
import com.deepon.smartdocs.document.repository.DocumentRepository;
import com.deepon.smartdocs.document.repository.DocumentSummaryProjection;
import com.deepon.smartdocs.document.service.DocumentService;
import com.deepon.smartdocs.revision.service.RevisionService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentServiceImpl implements DocumentService {

    private final DocumentRepository documentRepository;
    private final RevisionService revisionService;
    private final ContentValidator contentValidator;
    private final ContentHasher contentHasher;
    private final CursorCodec cursorCodec;
    private final Clock clock;
    private final int maxDocumentsPerUser;

    private final Counter saveTotal;
    private final Counter saveConflictTotal;
    private final Counter saveNoopTotal;

    public DocumentServiceImpl(DocumentRepository documentRepository,
                                RevisionService revisionService,
                                ContentValidator contentValidator,
                                ContentHasher contentHasher,
                                CursorCodec cursorCodec,
                                Clock clock,
                                MeterRegistry meterRegistry,
                                @Value("${smartdocs.document.max-per-user:500}") int maxDocumentsPerUser) {
        this.documentRepository = documentRepository;
        this.revisionService = revisionService;
        this.contentValidator = contentValidator;
        this.contentHasher = contentHasher;
        this.cursorCodec = cursorCodec;
        this.clock = clock;
        this.maxDocumentsPerUser = maxDocumentsPerUser;
        this.saveTotal = meterRegistry.counter("document_save_total");
        this.saveConflictTotal = meterRegistry.counter("document_save_conflict_total");
        this.saveNoopTotal = meterRegistry.counter("document_save_noop_total");
    }

    @Override
    @Transactional
    public Document create(Actor actor, String rawTitle, String rawContent) {
        String title = contentValidator.normalizeAndValidateTitle(rawTitle);
        String content = rawContent == null ? "" : rawContent;
        contentValidator.validateContent(content);

        // Two concurrent creates at the boundary may both pass this check;
        // the cap overshoots by at most the concurrency. Accepted and
        // documented, not fixed here (design doc section 10.5, 15).
        if (documentRepository.countByOwnerIdAndDeletedAtIsNull(actor.userId()) >= maxDocumentsPerUser) {
            throw new DocumentLimitReachedException(maxDocumentsPerUser);
        }

        String hash = contentHasher.hash(content);
        int sizeBytes = contentHasher.utf8SizeBytes(content);
        Instant now = clock.instant();

        Document document = new Document(UUID.randomUUID(), actor.userId(), title, content, hash, sizeBytes,
                1L, actor.actorId(), actor.actorId(), now, now);
        documentRepository.save(document);
        revisionService.recordRevision(document.getId(), 1L, content, hash, sizeBytes, actor.actorId(), now);
        return document;
    }

    @Override
    @Transactional(readOnly = true)
    public Document get(Actor actor, UUID id) {
        return documentRepository.findByIdAndOwnerIdAndDeletedAtIsNull(id, actor.userId())
                .orElseThrow(() -> new DocumentNotFoundException(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Page list(Actor actor, int limit, String cursor) {
        PageRequest pageable = PageRequest.of(0, limit);
        List<DocumentSummaryProjection> items = cursor == null
                ? documentRepository.findFirstPage(actor.userId(), pageable)
                : decodeAndFetchNextPage(actor, limit, cursor, pageable);

        String nextCursor = items.size() < limit || items.isEmpty()
                ? null
                : cursorCodec.encode(items.get(items.size() - 1).getUpdatedAt(), items.get(items.size() - 1).getId());
        return new Page(items, nextCursor);
    }

    private List<DocumentSummaryProjection> decodeAndFetchNextPage(Actor actor, int limit, String cursor, PageRequest pageable) {
        CursorCodec.Cursor decoded = cursorCodec.decode(cursor);
        return documentRepository.findNextPage(actor.userId(), decoded.updatedAt(), decoded.id(), pageable);
    }

    /**
     * Steps exactly follow design doc section 5.2. Steps 4 and 6 look
     * redundant; they are not. Step 4 gives the client the current content in
     * the error body without a second round trip. Step 6 is the actual
     * correctness guarantee — the atomic conditional UPDATE.
     */
    @Override
    @Transactional
    public Document updateContent(Actor actor, UUID id, long expectedVersion, String content, String clientHash) {
        saveTotal.increment();
        contentValidator.validateContent(content);
        String hash = contentHasher.hash(content);

        if (clientHash != null && !clientHash.equalsIgnoreCase(hash)) {
            throw new ContentHashMismatchException(clientHash, hash);
        }

        Document current = documentRepository.findByIdAndOwnerIdAndDeletedAtIsNull(id, actor.userId())
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
        int rows = documentRepository.updateContent(id, actor.userId(), expectedVersion, content, hash, sizeBytes, actor.actorId(), now);

        if (rows == 0) {
            saveConflictTotal.increment();
            throw resolveRaceAfterFailedWrite(actor, id, expectedVersion);
        }

        Document updated = documentRepository.findByIdAndOwnerIdAndDeletedAtIsNull(id, actor.userId())
                .orElseThrow(() -> new DocumentNotFoundException(id));
        revisionService.recordRevision(id, updated.getVersion(), content, hash, sizeBytes, actor.actorId(), now);
        return updated;
    }

    @Override
    @Transactional
    public Document rename(Actor actor, UUID id, long expectedVersion, String rawTitle) {
        String title = contentValidator.normalizeAndValidateTitle(rawTitle);

        Document current = documentRepository.findByIdAndOwnerIdAndDeletedAtIsNull(id, actor.userId())
                .orElseThrow(() -> new DocumentNotFoundException(id));
        if (current.getVersion() != expectedVersion) {
            throw versionMismatch(expectedVersion, current);
        }

        Instant now = clock.instant();
        int rows = documentRepository.updateTitle(id, actor.userId(), expectedVersion, title, actor.actorId(), now);
        if (rows == 0) {
            throw resolveRaceAfterFailedWrite(actor, id, expectedVersion);
        }

        return documentRepository.findByIdAndOwnerIdAndDeletedAtIsNull(id, actor.userId())
                .orElseThrow(() -> new DocumentNotFoundException(id));
    }

    @Override
    @Transactional
    public void softDelete(Actor actor, UUID id, long expectedVersion) {
        Document current = documentRepository.findByIdAndOwnerIdAndDeletedAtIsNull(id, actor.userId())
                .orElseThrow(() -> new DocumentNotFoundException(id));
        if (current.getVersion() != expectedVersion) {
            throw versionMismatch(expectedVersion, current);
        }

        Instant now = clock.instant();
        int rows = documentRepository.softDelete(id, actor.userId(), expectedVersion, actor.actorId(), now);
        if (rows == 0) {
            throw resolveRaceAfterFailedWrite(actor, id, expectedVersion);
        }
    }

    /**
     * Zero rows affected by the conditional UPDATE means one of three things:
     * the document is gone, soft-deleted, or someone else wrote first.
     * Distinguish by a follow-up read — never guess (design doc section 7.5).
     */
    private RuntimeException resolveRaceAfterFailedWrite(Actor actor, UUID id, long expectedVersion) {
        return documentRepository.findByIdAndOwnerIdAndDeletedAtIsNull(id, actor.userId())
                .<RuntimeException>map(reloaded -> versionMismatch(expectedVersion, reloaded))
                .orElseGet(() -> new DocumentNotFoundException(id));
    }

    private VersionMismatchException versionMismatch(long expectedVersion, Document current) {
        return new VersionMismatchException(expectedVersion, current.getVersion(), current.getContent(),
                current.getContentHash(), current.getContentSizeBytes());
    }
}
