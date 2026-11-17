package com.ycy.travelagent.chat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class ChatRepository {

    public record Session(String id, String userId, String title, boolean pinned,
                          Instant createdAt, Instant updatedAt) {
    }

    public record Message(long id, String sessionId, String role, String content,
                          Integer promptTokens, Integer completionTokens, BigDecimal cost,
                          Instant createdAt) {
    }

    private static final RowMapper<Session> SESSION_MAPPER = (rs, rowNum) -> new Session(
            rs.getString("id"),
            rs.getString("user_id"),
            rs.getString("title"),
            rs.getBoolean("pinned"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")));

    private static final RowMapper<Message> MESSAGE_MAPPER = (rs, rowNum) -> new Message(
            rs.getLong("id"),
            rs.getString("session_id"),
            rs.getString("role"),
            rs.getString("content"),
            (Integer) rs.getObject("prompt_tokens"),
            (Integer) rs.getObject("completion_tokens"),
            rs.getBigDecimal("cost"),
            toInstant(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbcTemplate;

    public ChatRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void createSession(String id, String userId, String title) {
        jdbcTemplate.update(
                "INSERT INTO chat_session (id, user_id, title) VALUES (?, ?, ?)",
                id, userId, title);
    }

    public List<Session> listSessions(String userId) {
        return jdbcTemplate.query("""
                SELECT * FROM chat_session
                WHERE user_id = ?
                ORDER BY pinned DESC, updated_at DESC
                """, SESSION_MAPPER, userId);
    }

    public Optional<Session> findSession(String id) {
        return jdbcTemplate.query("SELECT * FROM chat_session WHERE id = ?", SESSION_MAPPER, id)
                .stream().findFirst();
    }

    /** 只返回属于该用户的会话，越权与不存在同样处理。 */
    public Optional<Session> findOwned(String id, String userId) {
        return jdbcTemplate.query(
                        "SELECT * FROM chat_session WHERE id = ? AND user_id = ?",
                        SESSION_MAPPER, id, userId)
                .stream().findFirst();
    }

    public boolean rename(String id, String userId, String title, Boolean pinned) {
        int updated = jdbcTemplate.update("""
                UPDATE chat_session
                SET title = COALESCE(?, title),
                    pinned = COALESCE(?, pinned),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND user_id = ?
                """, title, pinned, id, userId);
        return updated > 0;
    }

    /** 首条用户消息生成会话标题；只有仍叫"新的对话"时才覆盖。 */
    public void titleFromFirstMessage(String id, String title) {
        jdbcTemplate.update("""
                UPDATE chat_session SET title = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND title = '新的对话'
                """, title, id);
    }

    public void touch(String id) {
        jdbcTemplate.update("UPDATE chat_session SET updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }

    public boolean deleteSession(String id, String userId) {
        Integer owned = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_session WHERE id = ? AND user_id = ?", Integer.class, id, userId);
        if (owned == null || owned == 0) {
            return false;
        }
        jdbcTemplate.update("DELETE FROM chat_message WHERE session_id = ?", id);
        jdbcTemplate.update("DELETE FROM chat_session WHERE id = ?", id);
        return true;
    }

    public void appendMessage(String sessionId, String role, String content,
                              Integer promptTokens, Integer completionTokens, BigDecimal cost) {
        jdbcTemplate.update("""
                INSERT INTO chat_message (session_id, role, content, prompt_tokens, completion_tokens, cost)
                VALUES (?, ?, ?, ?, ?, ?)
                """, sessionId, role, content, promptTokens, completionTokens, cost);
        touch(sessionId);
    }

    public List<Message> listMessages(String sessionId) {
        return jdbcTemplate.query(
                "SELECT * FROM chat_message WHERE session_id = ? ORDER BY id ASC",
                MESSAGE_MAPPER, sessionId);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
