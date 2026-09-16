package com.deepon.smartdocs;

import com.deepon.smartdocs.common.Actor;
import com.deepon.smartdocs.common.IdGenerator;
import com.deepon.smartdocs.document.entity.Document;
import com.deepon.smartdocs.document.ContentHasher;
import com.deepon.smartdocs.document.service.DocumentService;
import com.deepon.smartdocs.revision.service.RevisionService;
import com.deepon.smartdocs.support.AbstractPostgresTest;
import com.deepon.smartdocs.support.TestUsers;
import com.deepon.smartdocs.user.repository.UserRepository;
import com.deepon.smartdocs.user.service.AuthService;
import com.deepon.smartdocs.user.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design doc section 12, "Definition of done": content survives a full
 * application restart with an identical SHA-256 hash, and two application
 * instances against one database behave identically to one.
 *
 * We simulate "restart" and "a second instance" the same way: build a
 * brand-new, fully independent Spring Boot context — its own connection
 * pool, JPA session factory, Liquibase run, everything a real second JVM
 * would have — pointed at the same PostgreSQL container. Nothing at the
 * application layer survives that except what actually made it to disk.
 */
@SpringBootTest
class IntegrationSaveReloadTest extends AbstractPostgresTest {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private ContentHasher contentHasher;

    @Autowired
    private RevisionService revisionService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthService authService;

    @Autowired
    private IdGenerator idGenerator;

    @Autowired
    private Clock clock;

    private Actor actor;

    @BeforeEach
    void createTestUser() {
        actor = TestUsers.createActor(userRepository, idGenerator, clock);
    }

    private ConfigurableApplicationContext startIndependentInstance() {
        // SpringApplicationBuilder#properties adds a *default* (lowest-priority)
        // property source, which application.yaml would still win over. This
        // context isn't part of the test's Spring TestContext, so it never sees
        // the @ServiceConnection wiring the primary context gets for free —
        // TestPropertyValues.applyTo() adds a highest-priority source instead,
        // the same technique @DynamicPropertySource uses, so these values
        // actually override application.yaml's own datasource defaults.
        return new SpringApplicationBuilder(SmartdocsApplication.class)
                .web(WebApplicationType.NONE)
                .initializers(context -> TestPropertyValues.of(
                        "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword()
                ).applyTo(context))
                .run();
    }

    @Test
    void contentSurvivesAFullApplicationRestartWithIdenticalHash() {
        String content = "line one\nline two\nline three\n";
        Document created = documentService.create(actor, "Restart Test", content);
        UUID id = created.getId();
        String expectedHash = contentHasher.hash(content);
        assertThat(created.getContentHash()).isEqualTo(expectedHash);

        try (ConfigurableApplicationContext restarted = startIndependentInstance()) {
            DocumentService restartedService = restarted.getBean(DocumentService.class);
            Document reloaded = restartedService.get(actor, id);

            assertThat(reloaded.getContent()).isEqualTo(content);
            assertThat(reloaded.getContentHash()).isEqualTo(expectedHash);
            assertThat(reloaded.getVersion()).isEqualTo(1L);
        }
    }

    @Test
    void twoApplicationInstancesAgainstOneDatabaseBehaveIdenticallyToOne() {
        // Design doc section 3.2: two instances behind a load balancer must
        // work with zero code change because the service is stateless.
        try (ConfigurableApplicationContext second = startIndependentInstance()) {
            DocumentService serviceOnSecondInstance = second.getBean(DocumentService.class);

            Document created = documentService.create(actor, "Cross-instance", "hello from instance A");
            Document seenFromOther = serviceOnSecondInstance.get(actor, created.getId());

            assertThat(seenFromOther.getContent()).isEqualTo("hello from instance A");
            assertThat(seenFromOther.getVersion()).isEqualTo(created.getVersion());

            Document updatedFromOther = serviceOnSecondInstance.updateContent(
                    actor, created.getId(), created.getVersion(), "hello from instance B", null);
            Document seenFromFirst = documentService.get(actor, created.getId());

            assertThat(seenFromFirst.getContent()).isEqualTo("hello from instance B");
            assertThat(seenFromFirst.getVersion()).isEqualTo(updatedFromOther.getVersion());
        }
    }

    /**
     * Design doc section 11's other "two-instance" case, distinct from
     * {@link #twoApplicationInstancesAgainstOneDatabaseBehaveIdenticallyToOne}
     * above (which only exercises document ops): "login on one, authenticated
     * request on the other." A session created through {@code AuthService} on
     * this JVM's connection pool must resolve to the same {@code Actor} through
     * {@code SessionService} on a second, fully independent JVM — proving
     * session state really lives in PostgreSQL and not in any per-instance cache.
     */
    @Test
    void loginOnOneInstanceResolvesOnAnotherInstance() {
        String email = "cross-instance-" + UUID.randomUUID() + "@example.com";
        AuthService.AuthResult registered = authService.register(
                email, "a-long-enough-password", "Name", "JUnit/1.0", "ip-cross-instance-" + UUID.randomUUID());

        try (ConfigurableApplicationContext second = startIndependentInstance()) {
            SessionService sessionServiceOnSecondInstance = second.getBean(SessionService.class);

            Optional<SessionService.Resolved> resolved = sessionServiceOnSecondInstance.resolve(registered.rawToken());

            assertThat(resolved).isPresent();
            assertThat(resolved.get().actor().userId()).isEqualTo(registered.user().getId());
        }
    }

    @Test
    void noOpSaveDoesNotBumpVersionOrCreateRevision() {
        Document created = documentService.create(actor, "Noop", "same content");

        Document result = documentService.updateContent(actor, created.getId(), created.getVersion(), "same content", null);

        assertThat(result.getVersion()).isEqualTo(created.getVersion());
        assertThat(revisionService.listRevisions(actor, created.getId())).hasSize(1);
    }

    @Test
    void everySuccessfulContentSaveAppendsExactlyOneRevision() {
        Document created = documentService.create(actor, "Revisions", "v1");
        Document v2 = documentService.updateContent(actor, created.getId(), created.getVersion(), "v2", null);
        documentService.updateContent(actor, created.getId(), v2.getVersion(), "v3", null);

        assertThat(revisionService.listRevisions(actor, created.getId())).hasSize(3);
    }
}
