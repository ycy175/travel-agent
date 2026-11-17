package com.ycy.travelagent.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class UserRepository {

    private static final RowMapper<UserAccount> MAPPER = (rs, rowNum) -> new UserAccount(
            rs.getString("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("display_name"),
            rs.getString("employee_level"),
            rs.getString("role"),
            rs.getString("department"),
            rs.getBoolean("enabled"));

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<UserAccount> findByUsername(String username) {
        List<UserAccount> list = jdbcTemplate.query(
                "SELECT * FROM app_user WHERE username = ?", MAPPER, username);
        return list.stream().findFirst();
    }

    public Optional<UserAccount> findById(String id) {
        List<UserAccount> list = jdbcTemplate.query(
                "SELECT * FROM app_user WHERE id = ?", MAPPER, id);
        return list.stream().findFirst();
    }

    public List<UserAccount> findAll() {
        return jdbcTemplate.query("SELECT * FROM app_user ORDER BY id", MAPPER);
    }

    public void insert(String id, String username, String rawPassword,
                       String displayName, String level, String role, String department) {
        jdbcTemplate.update("""
                        INSERT INTO app_user (id, username, password_hash, display_name,
                                              employee_level, role, department, enabled)
                        VALUES (?, ?, ?, ?, ?, ?, ?, TRUE)
                        """,
                id, username, PasswordHasher.hash(rawPassword), displayName, level, role, department);
    }

    public long count() {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_user", Long.class);
        return value == null ? 0 : value;
    }
}
