package com.ycy.travelagent.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
public class AuditRepository {

    private static final RowMapper<AuditEntry> MAPPER = (rs, rowNum) -> new AuditEntry(
            rs.getLong("id"),
            rs.getString("user_id"),
            rs.getString("session_id"),
            rs.getString("action"),
            rs.getString("resource"),
            rs.getString("detail"),
            rs.getBoolean("success"),
            (Integer) rs.getObject("duration_ms"),
            rs.getString("ip"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public AuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(String userId, String sessionId, String action, String resource,
                       String detail, boolean success, Integer durationMs, String ip) {
        jdbcTemplate.update("""
                INSERT INTO audit_log
                    (user_id, session_id, action, resource, detail, success, duration_ms, ip)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, userId, sessionId, action, resource, detail, success, durationMs, ip);
    }

    public List<AuditEntry> list(int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM audit_log ORDER BY id DESC LIMIT ?", MAPPER, limit);
    }

    public List<AuditEntry> listByUser(String userId, int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM audit_log WHERE user_id = ? ORDER BY id DESC LIMIT ?",
                MAPPER, userId, limit);
    }
}
