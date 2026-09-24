package com.enterprise.admin.security;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared MySQL-backed store: every application instance counts into the same rows, so limits hold across a
 * horizontally scaled deployment without extra infrastructure.
 *
 * <p>The increment is one atomic statement ({@code INSERT … ON DUPLICATE KEY UPDATE}); {@code LAST_INSERT_ID(expr)}
 * returns the post-increment total on the same connection without a second read race. Each call runs in its own
 * short transaction ({@code REQUIRES_NEW}), so counting is never rolled back with a failing business transaction
 * (e.g. a rejected login).
 */
public class JdbcRateLimitStore implements RateLimitStore {

    private static final String UPSERT = """
            INSERT INTO rate_limit_buckets (bucket_key, window_start, hits, expires_at)
            VALUES (?, ?, LAST_INSERT_ID(1), ?)
            ON DUPLICATE KEY UPDATE hits = LAST_INSERT_ID(hits + 1)""";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JdbcRateLimitStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public long increment(String key, Instant windowStart, Instant expiresAt) {
        Long total = tx.execute(status -> jdbc.execute((java.sql.Connection connection) -> {
            try (PreparedStatement upsert = connection.prepareStatement(UPSERT)) {
                upsert.setString(1, key);
                upsert.setTimestamp(2, Timestamp.from(windowStart));
                upsert.setTimestamp(3, Timestamp.from(expiresAt));
                upsert.executeUpdate();
            }
            try (PreparedStatement read = connection.prepareStatement("SELECT LAST_INSERT_ID()");
                 ResultSet rs = read.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }));
        return total == null ? 0 : total;
    }

    @Override
    public long current(String key, Instant windowStart) {
        Long hits = tx.execute(status -> jdbc.query("SELECT hits FROM rate_limit_buckets WHERE bucket_key = ? AND window_start = ?",
                rs -> rs.next() ? rs.getLong(1) : 0L, key, Timestamp.from(windowStart)));
        return hits == null ? 0 : hits;
    }

    @Override
    public int purgeExpired(Instant now) {
        Integer deleted = tx.execute(status -> jdbc.update("DELETE FROM rate_limit_buckets WHERE expires_at < ?", Timestamp.from(now)));
        return deleted == null ? 0 : deleted;
    }
}
