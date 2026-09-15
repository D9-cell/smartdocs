package com.deepon.smartdocs.revision.service;

import com.deepon.smartdocs.document.entity.Document;
import com.deepon.smartdocs.document.service.DocumentService;
import com.deepon.smartdocs.document.exception.DocumentNotFoundException;
import com.deepon.smartdocs.revision.entity.DocumentRevision;
import com.deepon.smartdocs.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct coverage of every {@link RevisionService} method and its error
 * branches against a real database — the design doc's Definition of Done
 * (section 12) asks for >90% line coverage on this class specifically.
 */
@SpringBootTest
class RevisionServiceTest extends AbstractPostgresTest {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private RevisionService revisionService;

    @Test
    void listRevisionsOnMissingDocumentThrowsNotFound() {
        assertThatThrownBy(() -> revisionService.listRevisions(UUID.randomUUID()))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void listRevisionsOnDeletedDocumentThrowsNotFound() {
        Document created = documentService.create("Doc", "body");
        documentService.softDelete(created.getId(), created.getVersion());

        assertThatThrownBy(() -> revisionService.listRevisions(created.getId()))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void getRevisionReturnsFullHistoricalBody() {
        Document created = documentService.create("Doc", "v1");
        documentService.updateContent(created.getId(), created.getVersion(), "v2", null);

        DocumentRevision revisionOne = revisionService.getRevision(created.getId(), 1L);

        assertThat(revisionOne.getContent()).isEqualTo("v1");
        assertThat(revisionOne.getVersion()).isEqualTo(1L);
    }

    @Test
    void getRevisionForNonexistentVersionThrowsNotFound() {
        Document created = documentService.create("Doc", "v1");

        assertThatThrownBy(() -> revisionService.getRevision(created.getId(), 999L))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void getRevisionOnMissingDocumentThrowsNotFound() {
        assertThatThrownBy(() -> revisionService.getRevision(UUID.randomUUID(), 1L))
                .isInstanceOf(DocumentNotFoundException.class);
    }
}
