package com.deepon.smartdocs.document.repository;

import com.deepon.smartdocs.document.entity.Document;
import com.deepon.smartdocs.revision.entity.DocumentRevision;
import com.deepon.smartdocs.revision.repository.DocumentRevisionRepository;
import com.deepon.smartdocs.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Proves the conditional-update row counts (design doc section 4.2, 7.5) and
 * the schema-level invariants (unique constraint, soft-delete exclusion,
 * owner scoping) against a real PostgreSQL, not an in-memory substitute.
 * Documents are owned by the fixed system-user id seeded by changeset 009 —
 * it exists as soon as Liquibase runs, and this slice only cares that the
 * owner FK target is valid, not who it is.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class DocumentRepositoryTest extends AbstractPostgresTest {

    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_OWNER_ID = UUID.randomUUID(); // never persisted as an app_user — see ownership tests below

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentRevisionRepository revisionRepository;

    private Document persistDocument(long version) {
        Instant now = Instant.now();
        Document document = new Document(UUID.randomUUID(), OWNER_ID, "Notes", "hello", "hash1", 5,
                version, "anonymous", "anonymous", now, now);
        return documentRepository.saveAndFlush(document);
    }

    @Test
    void conditionalUpdateReturnsOneRowOnVersionMatch() {
        Document document = persistDocument(1);

        int rows = documentRepository.updateContent(document.getId(), OWNER_ID, 1L, "updated", "hash2", 7,
                "anonymous", Instant.now());

        assertThat(rows).isEqualTo(1);
        Document reloaded = documentRepository.findByIdAndOwnerIdAndDeletedAtIsNull(document.getId(), OWNER_ID).orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(2L);
        assertThat(reloaded.getContent()).isEqualTo("updated");
    }

    @Test
    void conditionalUpdateReturnsZeroRowsOnVersionMismatch() {
        Document document = persistDocument(1);

        int rows = documentRepository.updateContent(document.getId(), OWNER_ID, 99L, "updated", "hash2", 7,
                "anonymous", Instant.now());

        assertThat(rows).isZero();
        Document reloaded = documentRepository.findByIdAndOwnerIdAndDeletedAtIsNull(document.getId(), OWNER_ID).orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(1L); // untouched
    }

    @Test
    void conditionalUpdateReturnsZeroRowsForAWrongOwner() {
        Document document = persistDocument(1);

        // A UUID that owns nothing — proves the WHERE clause, not a fetch-then-check, is what blocks this.
        int rows = documentRepository.updateContent(document.getId(), OTHER_OWNER_ID, 1L, "updated", "hash2", 7,
                "anonymous", Instant.now());

        assertThat(rows).isZero();
        assertThat(documentRepository.findByIdAndOwnerIdAndDeletedAtIsNull(document.getId(), OTHER_OWNER_ID)).isEmpty();
        assertThat(documentRepository.findByIdAndOwnerIdAndDeletedAtIsNull(document.getId(), OWNER_ID)).isPresent();
    }

    @Test
    void conditionalUpdateExcludesSoftDeletedRows() {
        Document document = persistDocument(1);
        documentRepository.softDelete(document.getId(), OWNER_ID, 1L, "anonymous", Instant.now());

        int rows = documentRepository.updateContent(document.getId(), OWNER_ID, 2L, "updated", "hash2", 7,
                "anonymous", Instant.now());

        assertThat(rows).isZero();
        assertThat(documentRepository.findByIdAndOwnerIdAndDeletedAtIsNull(document.getId(), OWNER_ID)).isEmpty();
    }

    @Test
    void findByIdAndOwnerIdAndDeletedAtIsNullHidesSoftDeletedDocuments() {
        Document document = persistDocument(1);
        documentRepository.softDelete(document.getId(), OWNER_ID, 1L, "anonymous", Instant.now());

        assertThat(documentRepository.findByIdAndOwnerIdAndDeletedAtIsNull(document.getId(), OWNER_ID)).isEmpty();
        assertThat(documentRepository.findById(document.getId())).isPresent(); // still exists via cascade-safe lookup
    }

    @Test
    void duplicateRevisionVersionViolatesUniqueConstraint() {
        Document document = persistDocument(1);
        UUID documentId = document.getId();
        revisionRepository.saveAndFlush(new DocumentRevision(UUID.randomUUID(), documentId, 1L,
                "hello", "hash1", 5, "anonymous", "HUMAN", Instant.now(), null, "REST", null));

        assertThatThrownBy(() -> revisionRepository.saveAndFlush(new DocumentRevision(UUID.randomUUID(), documentId, 1L,
                "hello again", "hash3", 11, "anonymous", "HUMAN", Instant.now(), null, "REST", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void summaryProjectionNeverExposesContent() {
        persistDocument(1);

        List<DocumentSummaryProjection> summaries = documentRepository.findFirstPage(OWNER_ID, PageRequest.of(0, 10));

        assertThat(summaries).isNotEmpty();
        // DocumentSummaryProjection has no getContent() method at all: this is a
        // compile-time guarantee, not just a runtime check. Reaching this line
        // without a compile error is the proof.
        assertThat(summaries.get(0).getTitle()).isNotNull();
    }

    @Test
    void firstPageNeverReturnsAnotherOwnersDocuments() {
        persistDocument(1);

        List<DocumentSummaryProjection> summaries = documentRepository.findFirstPage(OTHER_OWNER_ID, PageRequest.of(0, 10));

        assertThat(summaries).isEmpty();
    }

    @Test
    void nextPageExcludesRowsAtOrAfterTheCursor() {
        Document older = persistDocument(1);
        Document newer = persistDocument(1);
        // Ensure a stable ordering to page against, independent of wall-clock resolution.
        documentRepository.updateTitle(newer.getId(), OWNER_ID, 1L, "Newer", "anonymous",
                older.getUpdatedAt().plusSeconds(5));

        Document reloadedNewer = documentRepository.findByIdAndOwnerIdAndDeletedAtIsNull(newer.getId(), OWNER_ID).orElseThrow();
        List<DocumentSummaryProjection> nextPage = documentRepository.findNextPage(
                OWNER_ID, reloadedNewer.getUpdatedAt(), reloadedNewer.getId(), PageRequest.of(0, 10));

        assertThat(nextPage).extracting(DocumentSummaryProjection::getId).contains(older.getId());
        assertThat(nextPage).extracting(DocumentSummaryProjection::getId).doesNotContain(newer.getId());
    }

    @Test
    void updateTitleIncrementsVersionUnderTheSameGuard() {
        Document document = persistDocument(1);

        int rows = documentRepository.updateTitle(document.getId(), OWNER_ID, 1L, "Renamed", "anonymous", Instant.now());

        assertThat(rows).isEqualTo(1);
        Document reloaded = documentRepository.findByIdAndOwnerIdAndDeletedAtIsNull(document.getId(), OWNER_ID).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("Renamed");
        assertThat(reloaded.getVersion()).isEqualTo(2L);
    }
}
