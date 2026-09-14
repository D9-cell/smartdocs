package com.deepon.statecore;

import com.deepon.statecore.domain.Document;
import com.deepon.statecore.service.ContentHasher;
import com.deepon.statecore.service.DocumentService;
import com.deepon.statecore.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ConfigurableApplicationContext;

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

    private ConfigurableApplicationContext startIndependentInstance() {
        // SpringApplicationBuilder#properties adds a *default* (lowest-priority)
        // property source, which application.yaml would still win over. This
        // context isn't part of the test's Spring TestContext, so it never sees
        // the @ServiceConnection wiring the primary context gets for free —
        // TestPropertyValues.applyTo() adds a highest-priority source instead,
        // the same technique @DynamicPropertySource uses, so these values
        // actually override application.yaml's own datasource defaults.
        return new SpringApplicationBuilder(StateCoreApplication.class)
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
        Document created = documentService.create("Restart Test", content);
        UUID id = created.getId();
        String expectedHash = contentHasher.hash(content);
        assertThat(created.getContentHash()).isEqualTo(expectedHash);

        try (ConfigurableApplicationContext restarted = startIndependentInstance()) {
            DocumentService restartedService = restarted.getBean(DocumentService.class);
            Document reloaded = restartedService.get(id);

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

            Document created = documentService.create("Cross-instance", "hello from instance A");
            Document seenFromOther = serviceOnSecondInstance.get(created.getId());

            assertThat(seenFromOther.getContent()).isEqualTo("hello from instance A");
            assertThat(seenFromOther.getVersion()).isEqualTo(created.getVersion());

            Document updatedFromOther = serviceOnSecondInstance.updateContent(
                    created.getId(), created.getVersion(), "hello from instance B", null);
            Document seenFromFirst = documentService.get(created.getId());

            assertThat(seenFromFirst.getContent()).isEqualTo("hello from instance B");
            assertThat(seenFromFirst.getVersion()).isEqualTo(updatedFromOther.getVersion());
        }
    }

    @Test
    void noOpSaveDoesNotBumpVersionOrCreateRevision() {
        Document created = documentService.create("Noop", "same content");

        Document result = documentService.updateContent(created.getId(), created.getVersion(), "same content", null);

        assertThat(result.getVersion()).isEqualTo(created.getVersion());
        assertThat(documentService.listRevisions(created.getId())).hasSize(1);
    }

    @Test
    void everySuccessfulContentSaveAppendsExactlyOneRevision() {
        Document created = documentService.create("Revisions", "v1");
        Document v2 = documentService.updateContent(created.getId(), created.getVersion(), "v2", null);
        documentService.updateContent(created.getId(), v2.getVersion(), "v3", null);

        assertThat(documentService.listRevisions(created.getId())).hasSize(3);
    }
}
