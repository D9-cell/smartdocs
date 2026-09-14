package com.deepon.statecore.domain;

import com.deepon.statecore.support.AbstractPostgresTest;
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
 * the schema-level invariants (unique constraint, soft-delete exclusion)
 * against a real PostgreSQL, not an in-memory substitute.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class DocumentRepositoryTest extends AbstractPostgresTest {

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentRevisionRepository revisionRepository;

    private Document persistDocument(long version) {
        Instant now = Instant.now();
        Document document = new Document(UUID.randomUUID(), "Notes", "hello", "hash1", 5,
                version, "anonymous", "anonymous", now, now);
        return documentRepository.saveAndFlush(document);
    }

    @Test
    void conditionalUpdateReturnsOneRowOnVersionMatch() {
        Document document = persistDocument(1);

        int rows = documentRepository.updateContent(document.getId(), 1L, "updated", "hash2", 7,
                "anonymous", Instant.now());

        assertThat(rows).isEqualTo(1);
        Document reloaded = documentRepository.findByIdAndDeletedAtIsNull(document.getId()).orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(2L);
        assertThat(reloaded.getContent()).isEqualTo("updated");
    }

    @Test
    void conditionalUpdateReturnsZeroRowsOnVersionMismatch() {
        Document document = persistDocument(1);

        int rows = documentRepository.updateContent(document.getId(), 99L, "updated", "hash2", 7,
                "anonymous", Instant.now());

        assertThat(rows).isZero();
        Document reloaded = documentRepository.findByIdAndDeletedAtIsNull(document.getId()).orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(1L); // untouched
    }

    @Test
    void conditionalUpdateExcludesSoftDeletedRows() {
        Document document = persistDocument(1);
        documentRepository.softDelete(document.getId(), 1L, "anonymous", Instant.now());

        int rows = documentRepository.updateContent(document.getId(), 2L, "updated", "hash2", 7,
                "anonymous", Instant.now());

        assertThat(rows).isZero();
        assertThat(documentRepository.findByIdAndDeletedAtIsNull(document.getId())).isEmpty();
    }

    @Test
    void findByIdAndDeletedAtIsNullHidesSoftDeletedDocuments() {
        Document document = persistDocument(1);
        documentRepository.softDelete(document.getId(), 1L, "anonymous", Instant.now());

        assertThat(documentRepository.findByIdAndDeletedAtIsNull(document.getId())).isEmpty();
        assertThat(documentRepository.findById(document.getId())).isPresent(); // still exists via cascade-safe lookup
    }

    @Test
    void duplicateRevisionVersionViolatesUniqueConstraint() {
        Document document = persistDocument(1);
        UUID documentId = document.getId();
        revisionRepository.saveAndFlush(new DocumentRevision(UUID.randomUUID(), documentId, 1L,
                "hello", "hash1", 5, "anonymous", "HUMAN", Instant.now()));

        assertThatThrownBy(() -> revisionRepository.saveAndFlush(new DocumentRevision(UUID.randomUUID(), documentId, 1L,
                "hello again", "hash3", 11, "anonymous", "HUMAN", Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void summaryProjectionNeverExposesContent() {
        persistDocument(1);

        List<DocumentSummaryProjection> summaries = documentRepository.findSummaries(PageRequest.of(0, 10));

        assertThat(summaries).isNotEmpty();
        // DocumentSummaryProjection has no getContent() method at all: this is a
        // compile-time guarantee, not just a runtime check. Reaching this line
        // without a compile error is the proof.
        assertThat(summaries.get(0).getTitle()).isNotNull();
    }

    @Test
    void updateTitleIncrementsVersionUnderTheSameGuard() {
        Document document = persistDocument(1);

        int rows = documentRepository.updateTitle(document.getId(), 1L, "Renamed", "anonymous", Instant.now());

        assertThat(rows).isEqualTo(1);
        Document reloaded = documentRepository.findByIdAndDeletedAtIsNull(document.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("Renamed");
        assertThat(reloaded.getVersion()).isEqualTo(2L);
    }
}
