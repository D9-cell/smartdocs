package com.deepon.smartdocs.user.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Purges rows {@code user_session} and {@code login_attempt} no longer need
 * to keep (design doc section 10.8). Guarded by a Postgres advisory lock so
 * two application instances against the same database don't both run it —
 * {@code pg_try_advisory_lock}/{@code pg_advisory_unlock} are session-scoped,
 * so both calls are made on one explicitly-held JDBC {@link Connection}
 * rather than through the connection-per-call {@code JdbcTemplate} pattern.
 *
 * The actual deletes live on {@link SessionCleanupRunner}, a separate
 * {@code @Transactional} bean — calling a custom {@code @Modifying} query
 * bare from this class's own {@code @Scheduled} method (no ambient
 * transaction, and {@code @Transactional} on a self-invoked method here
 * would be silently ignored by Spring's proxy) fails at flush time exactly
 * the way it does inside a test with no surrounding transaction.
 */
@Component
public class SessionCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(SessionCleanupJob.class);
    private static final long ADVISORY_LOCK_KEY = 72700100427L;

    private final DataSource dataSource;
    private final SessionCleanupRunner runner;

    public SessionCleanupJob(DataSource dataSource, SessionCleanupRunner runner) {
        this.dataSource = dataSource;
        this.runner = runner;
    }

    @Scheduled(fixedDelayString = "${smartdocs.cleanup.interval-ms:3600000}")
    public void run() {
        try (Connection connection = dataSource.getConnection()) {
            if (!tryLock(connection)) {
                log.debug("Cleanup lock held by another instance, skipping this run.");
                return;
            }
            try {
                SessionCleanupRunner.Result result = runner.purge();
                log.info("Session cleanup: removed {} expired sessions, {} stale login attempts.",
                        result.sessionsDeleted(), result.attemptsDeleted());
            } finally {
                unlock(connection);
            }
        } catch (SQLException e) {
            log.error("Cleanup job failed to acquire its database connection.", e);
        }
    }

    private boolean tryLock(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, ADVISORY_LOCK_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private void unlock(Connection connection) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, ADVISORY_LOCK_KEY);
            ps.execute();
        } catch (SQLException e) {
            log.warn("Failed to release cleanup advisory lock; it will clear when this connection closes.", e);
        }
    }
}
