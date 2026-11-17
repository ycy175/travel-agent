package com.ycy.travelagent.approval;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class ApprovalRepository {

    private static final RowMapper<Approval> MAPPER = (rs, rowNum) -> new Approval(
            rs.getString("id"),
            rs.getString("user_id"),
            rs.getString("session_id"),
            rs.getString("itinerary"),
            rs.getBigDecimal("cost"),
            ApprovalStatus.valueOf(rs.getString("status")),
            rs.getString("reject_reason"),
            rs.getString("approver_id"),
            toInstant(rs.getTimestamp("approved_at")),
            rs.getInt("version"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")));

    private final JdbcTemplate jdbcTemplate;

    public ApprovalRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(Approval approval) {
        jdbcTemplate.update("""
                INSERT INTO travel_approval
                    (id, user_id, session_id, itinerary, cost, status, version)
                VALUES (?, ?, ?, ?, ?, ?, 0)
                """,
                approval.id(), approval.userId(), approval.sessionId(), approval.itinerary(),
                approval.cost(), approval.status().name());
    }

    public Optional<Approval> findById(String id) {
        return jdbcTemplate.query("SELECT * FROM travel_approval WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public List<Approval> listBySession(String sessionId) {
        return jdbcTemplate.query(
                "SELECT * FROM travel_approval WHERE session_id = ? ORDER BY created_at DESC",
                MAPPER, sessionId);
    }

    public List<Approval> listByUser(String userId) {
        return jdbcTemplate.query(
                "SELECT * FROM travel_approval WHERE user_id = ? ORDER BY created_at DESC",
                MAPPER, userId);
    }

    /** 审批人看到的待办：不是自己发起的 PENDING 单据。 */
    public List<Approval> listPendingExcluding(String userId) {
        return jdbcTemplate.query(
                "SELECT * FROM travel_approval WHERE status = 'PENDING' AND user_id <> ? ORDER BY created_at ASC",
                MAPPER, userId);
    }

    /**
     * 带乐观锁的状态更新：WHERE 同时校验 version 与旧状态，
     * 并发点击"通过/驳回"只会有一个成功。
     */
    public int updateStatus(String id, int version, ApprovalStatus from, ApprovalStatus to,
                            String approverId, String reason) {
        return jdbcTemplate.update("""
                UPDATE travel_approval
                SET status = ?, approver_id = ?, reject_reason = ?,
                    approved_at = CASE WHEN ? = 'APPROVED' THEN CURRENT_TIMESTAMP ELSE approved_at END,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND version = ? AND status = ?
                """,
                to.name(), approverId, reason, to.name(), id, version, from.name());
    }

    private static java.time.Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
