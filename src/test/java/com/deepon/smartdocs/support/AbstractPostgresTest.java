package com.deepon.smartdocs.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A single real PostgreSQL 16 container, shared across every test class
 * that extends this one (static field, inherited — there is only one copy
 * of it in the whole JVM, per normal Java static-field semantics). Design
 * doc section 9: "Never use H2. H2 accepts SQL PostgreSQL rejects and
 * rejects SQL PostgreSQL accepts."
 *
 * Deliberately NOT annotated with {@code @Testcontainers}/{@code @Container}:
 * that JUnit5 extension ties start/stop to each individual test class's
 * before-all/after-all, and every subclass would install its own pair of
 * hooks on this one shared field — the first subclass to finish its run
 * would stop the container out from under every subclass still to come.
 * This is Testcontainers' own documented "singleton container" pattern:
 * start it once, eagerly, and let the Ryuk reaper / JVM shutdown hook
 * (registered automatically on first use) clean it up when the JVM exits.
 */
public abstract class AbstractPostgresTest {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    static {
        POSTGRES.start();
    }
}
