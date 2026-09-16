package com.deepon.smartdocs.revision.service;

import com.deepon.smartdocs.common.Actor;
import com.deepon.smartdocs.common.IdGenerator;
import com.deepon.smartdocs.document.entity.Document;
import com.deepon.smartdocs.document.service.DocumentService;
import com.deepon.smartdocs.document.exception.DocumentNotFoundException;
import com.deepon.smartdocs.revision.entity.DocumentRevision;
import com.deepon.smartdocs.support.AbstractPostgresTest;
import com.deepon.smartdocs.support.TestUsers;
import com.deepon.smartdocs.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
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

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private IdGenerator idGenerator;

    @Autowired
    private Clock clock;

    private Actor actor;
    private Actor otherActor;

    @BeforeEach
    void createTestUsers() {
        actor = TestUsers.createActor(userRepository, idGenerator, clock);
        otherActor = TestUsers.createActor(userRepository, idGenerator, clock);
    }

    @Test
    void listRevisionsOnMissingDocumentThrowsNotFound() {
        assertThatThrownBy(() -> revisionService.listRevisions(actor, UUID.randomUUID()))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void listRevisionsOnDeletedDocumentThrowsNotFound() {
        Document created = documentService.create(actor, "Doc", "body");
        documentService.softDelete(actor, created.getId(), created.getVersion());

        assertThatThrownBy(() -> revisionService.listRevisions(actor, created.getId()))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void listRevisionsOnAnotherOwnersDocumentThrowsNotFound() {
        Document created = documentService.create(actor, "Doc", "body");

        assertThatThrownBy(() -> revisionService.listRevisions(otherActor, created.getId()))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void getRevisionReturnsFullHistoricalBody() {
        Document created = documentService.create(actor, "Doc", "v1");
        documentService.updateContent(actor, created.getId(), created.getVersion(), "v2", null);

        DocumentRevision revisionOne = revisionService.getRevision(actor, created.getId(), 1L);

        assertThat(revisionOne.getContent()).isEqualTo("v1");
        assertThat(revisionOne.getVersion()).isEqualTo(1L);
    }

    @Test
    void getRevisionForNonexistentVersionThrowsNotFound() {
        Document created = documentService.create(actor, "Doc", "v1");

        assertThatThrownBy(() -> revisionService.getRevision(actor, created.getId(), 999L))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void getRevisionOnMissingDocumentThrowsNotFound() {
        assertThatThrownBy(() -> revisionService.getRevision(actor, UUID.randomUUID(), 1L))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void getRevisionOnAnotherOwnersDocumentThrowsNotFound() {
        Document created = documentService.create(actor, "Doc", "v1");

        assertThatThrownBy(() -> revisionService.getRevision(otherActor, created.getId(), 1L))
                .isInstanceOf(DocumentNotFoundException.class);
    }
}
