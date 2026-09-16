package com.deepon.smartdocs;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design doc section 7.6: "point Liquibase at an empty database, run
 * update, then run rollback stage-0 and confirm a clean empty schema." Uses
 * its own dedicated, genuinely-empty container rather than the shared one
 * from {@code AbstractPostgresTest}, and drives Liquibase directly (not
 * through Spring Boot's auto-run-at-startup) so update and rollback can each
 * be asserted independently.
 */
@Testcontainers
class MigrationRollbackTest {

    private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Test
    void updateThenRollbackStage0LeavesACleanEmptySchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);

            liquibase.update(new Contexts(), new LabelExpression());
            assertThat(applicationTableNames(connection)).containsExactlyInAnyOrder(
                    "document", "document_revision", "app_user", "user_session", "login_attempt");

            // `rollback <tag>` reverts everything deployed *after* the tag.
            // Changeset 004 (the tagDatabase changeset itself) is the last
            // changeset in the changelog, so rolling back "to" stage-0 from a
            // database that is already sitting exactly at that tag is
            // correctly a no-op — there is nothing after the tag to undo.
            // Proving the *full* rollback path clean (what this test and the
            // design doc's Definition of Done actually care about) means
            // rolling back every changeset by count instead.
            int changeSetCount = liquibase.getDatabaseChangeLog().getChangeSets().size();
            liquibase.rollback(changeSetCount, new Contexts(), new LabelExpression());
            assertThat(applicationTableNames(connection)).isEmpty();
        }
    }

    /**
     * The doc's exact recipe (section 8.5 verification note): "point
     * Liquibase at an empty database, run update, then rollback stage-0,
     * confirm only Stage 0 objects remain, then update again." Distinct from
     * the test above — this rolls back to a named tag partway through the
     * changelog rather than unwinding everything, and then proves the
     * changesets are safe to re-apply going forward from that point.
     */
    @Test
    void rollbackToStage0LeavesOnlyStage0ObjectsThenUpdateAgainRestoresStage1() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);

            liquibase.update(new Contexts(), new LabelExpression());
            assertThat(applicationTableNames(connection)).containsExactlyInAnyOrder(
                    "document", "document_revision", "app_user", "user_session", "login_attempt");

            liquibase.rollback("stage-0", new Contexts(), new LabelExpression());
            assertThat(applicationTableNames(connection)).containsExactlyInAnyOrder("document", "document_revision");

            liquibase.update(new Contexts(), new LabelExpression());
            assertThat(applicationTableNames(connection)).containsExactlyInAnyOrder(
                    "document", "document_revision", "app_user", "user_session", "login_attempt");
        }
    }

    /** Every table in the public schema except Liquibase's own bookkeeping tables. */
    private static java.util.List<String> applicationTableNames(Connection connection) throws Exception {
        java.util.List<String> tables = new java.util.ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                     SELECT table_name FROM information_schema.tables
                     WHERE table_schema = 'public'
                       AND table_name NOT LIKE 'databasechangelog%'
                     """)) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }
}
