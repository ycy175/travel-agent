package com.ycy.travelagent.policy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class PolicyRepository {

    private static final RowMapper<Policy> MAPPER = (rs, rowNum) -> new Policy(
            rs.getString("id"),
            rs.getString("employee_level"),
            rs.getString("city"),
            rs.getString("flight_class"),
            rs.getBigDecimal("hotel_limit"),
            rs.getBigDecimal("daily_allowance"),
            rs.getInt("advance_days"));

    private final JdbcTemplate jdbcTemplate;

    public PolicyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 城市精确匹配优先，其次回落到该职级的默认规则。 */
    public Optional<Policy> find(String level, String city) {
        List<Policy> exact = jdbcTemplate.query(
                "SELECT * FROM travel_policy WHERE employee_level = ? AND city = ?",
                MAPPER, level, city);
        if (!exact.isEmpty()) {
            return exact.stream().findFirst();
        }
        return jdbcTemplate.query(
                        "SELECT * FROM travel_policy WHERE employee_level = ? AND city = '*'",
                        MAPPER, level)
                .stream().findFirst();
    }

    public List<Policy> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM travel_policy ORDER BY employee_level, city", MAPPER);
    }

    public long count() {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM travel_policy", Long.class);
        return value == null ? 0 : value;
    }

    public void insert(Policy policy) {
        jdbcTemplate.update("""
                INSERT INTO travel_policy
                    (id, employee_level, city, flight_class, hotel_limit, daily_allowance, advance_days)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                policy.id(), policy.employeeLevel(), policy.city(), policy.flightClass(),
                policy.hotelLimit(), policy.dailyAllowance(), policy.advanceDays());
    }
}
