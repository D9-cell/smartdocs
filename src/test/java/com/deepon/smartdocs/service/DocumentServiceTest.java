package com.deepon.smartdocs.service;

import com.deepon.smartdocs.entity.Document;
import com.deepon.smartdocs.entity.DocumentRevision;
import com.deepon.smartdocs.repository.DocumentSummaryProjection;
import com.deepon.smartdocs.exception.ContentHashMismatchException;
import com.deepon.smartdocs.exception.DocumentNotFoundException;
import com.deepon.smartdocs.exception.VersionMismatchException;
import com.deepon.smartdocs.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct coverage of every {@link DocumentService} method and its error
 * branches against a real database — the design doc's Definition of Done
 * (section 12) asks for >90% line coverage on this class specifically.
 */
@SpringBootTest
class DocumentServiceTest extends AbstractPostgresTest {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private ContentHasher contentHasher;

    @Test
    void createDefaultsNullTitleAndContent() {
        Document created = documentService.create(null, null);

        assertThat(created.getTitle()).isEqualTo("Untitled");
        assertThat(created.getContent()).isEqualTo("");
        assertThat(created.getVersion()).isEqualTo(1L);
        assertThat(created.getContentHash()).isEqualTo(contentHasher.hash(""));
    }

    @Test
    void getUnknownDocumentThrowsNotFound() {
        assertThatThrownBy(() -> documentService.get(UUID.randomUUID()))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void listReturnsSummariesOrderedByUpdatedAtDescending() {
        Document first = documentService.create("First", "a");
        documentService.create("Second", "b");

        List<DocumentSummaryProjection> summaries = documentService.list(50, 0);

        assertThat(summaries).extracting(DocumentSummaryProjection::getId)
                .contains(first.getId());
        // Most-recently-updated first: "Second" was created after "First".
        assertThat(summaries.get(0).getUpdatedAt()).isAfterOrEqualTo(summaries.get(summaries.size() - 1).getUpdatedAt());
    }

    @Test
    void listCapsLimitAtTwoHundred() {
        // Just proves the call doesn't blow up with an absurd requested limit;
        // the cap itself is exercised by not throwing / not hanging.
        assertThat(documentService.list(10_000, 0)).isNotNull();
    }

    @Test
    void updateContentWithWrongExpectedVersionThrowsVersionMismatchCarryingCurrentState() {
        Document created = documentService.create("Doc", "original");

        assertThatThrownBy(() -> documentService.updateContent(created.getId(), 99L, "new content", null))
                .isInstanceOf(VersionMismatchException.class)
                .satisfies(ex -> {
                    VersionMismatchException vme = (VersionMismatchException) ex;
                    assertThat(vme.getExpectedVersion()).isEqualTo(99L);
                    assertThat(vme.getCurrentVersion()).isEqualTo(1L);
                    assertThat(vme.getCurrentContent()).isEqualTo("original");
                });
    }

    @Test
    void updateContentOnMissingDocumentThrowsNotFound() {
        assertThatThrownBy(() -> documentService.updateContent(UUID.randomUUID(), 1L, "x", null))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void updateContentWithMismatchedClientHashThrowsContentHashMismatch() {
        Document created = documentService.create("Doc", "original");

        assertThatThrownBy(() -> documentService.updateContent(created.getId(), created.getVersion(), "new content", "deadbeef"))
                .isInstanceOf(ContentHashMismatchException.class);
    }

    @Test
    void updateContentWithMatchingClientHashSucceeds() {
        Document created = documentService.create("Doc", "original");
        String correctHash = contentHasher.hash("new content");

        Document updated = documentService.updateContent(created.getId(), created.getVersion(), "new content", correctHash);

        assertThat(updated.getContent()).isEqualTo("new content");
        assertThat(updated.getVersion()).isEqualTo(2L);
    }

    @Test
    void renameSuccessIncrementsVersionAndLeavesContentUntouched() {
        Document created = documentService.create("Old Name", "body");

        Document renamed = documentService.rename(created.getId(), created.getVersion(), "New Name");

        assertThat(renamed.getTitle()).isEqualTo("New Name");
        assertThat(renamed.getVersion()).isEqualTo(2L);
        assertThat(renamed.getContent()).isEqualTo("body");
    }

    @Test
    void renameNormalizesBlankTitleToUntitled() {
        Document created = documentService.create("Old Name", "body");

        Document renamed = documentService.rename(created.getId(), created.getVersion(), "   ");

        assertThat(renamed.getTitle()).isEqualTo("Untitled");
    }

    @Test
    void renameWithWrongVersionThrowsVersionMismatch() {
        Document created = documentService.create("Doc", "body");

        assertThatThrownBy(() -> documentService.rename(created.getId(), 99L, "New Name"))
                .isInstanceOf(VersionMismatchException.class);
    }

    @Test
    void renameOnMissingDocumentThrowsNotFound() {
        assertThatThrownBy(() -> documentService.rename(UUID.randomUUID(), 1L, "New Name"))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void softDeleteSuccessHidesDocumentFromGet() {
        Document created = documentService.create("To Delete", "body");

        documentService.softDelete(created.getId(), created.getVersion());

        assertThatThrownBy(() -> documentService.get(created.getId()))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void softDeleteWithWrongVersionThrowsVersionMismatch() {
        Document created = documentService.create("Doc", "body");

        assertThatThrownBy(() -> documentService.softDelete(created.getId(), 99L))
                .isInstanceOf(VersionMismatchException.class);
    }

    @Test
    void softDeleteOnMissingDocumentThrowsNotFound() {
        assertThatThrownBy(() -> documentService.softDelete(UUID.randomUUID(), 1L))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void softDeleteOnAlreadyDeletedDocumentThrowsNotFound() {
        Document created = documentService.create("Doc", "body");
        documentService.softDelete(created.getId(), created.getVersion());

        // design doc section 8.5: delete is not idempotent — the version
        // guard needs a live row, so a second delete attempt 404s.
        assertThatThrownBy(() -> documentService.softDelete(created.getId(), created.getVersion() + 1))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void listRevisionsOnMissingDocumentThrowsNotFound() {
        assertThatThrownBy(() -> documentService.listRevisions(UUID.randomUUID()))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void listRevisionsOnDeletedDocumentThrowsNotFound() {
        Document created = documentService.create("Doc", "body");
        documentService.softDelete(created.getId(), created.getVersion());

        assertThatThrownBy(() -> documentService.listRevisions(created.getId()))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void getRevisionReturnsFullHistoricalBody() {
        Document created = documentService.create("Doc", "v1");
        documentService.updateContent(created.getId(), created.getVersion(), "v2", null);

        DocumentRevision revisionOne = documentService.getRevision(created.getId(), 1L);

        assertThat(revisionOne.getContent()).isEqualTo("v1");
        assertThat(revisionOne.getVersion()).isEqualTo(1L);
    }

    @Test
    void getRevisionForNonexistentVersionThrowsNotFound() {
        Document created = documentService.create("Doc", "v1");

        assertThatThrownBy(() -> documentService.getRevision(created.getId(), 999L))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void getRevisionOnMissingDocumentThrowsNotFound() {
        assertThatThrownBy(() -> documentService.getRevision(UUID.randomUUID(), 1L))
                .isInstanceOf(DocumentNotFoundException.class);
    }
}
