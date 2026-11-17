package com.ycy.travelagent.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class OrderRepository {

    private static final RowMapper<TravelOrder> MAPPER = (rs, rowNum) -> new TravelOrder(
            rs.getString("id"),
            rs.getString("approval_id"),
            rs.getString("user_id"),
            rs.getString("type"),
            rs.getString("supplier"),
            rs.getString("supplier_order_no"),
            rs.getString("title"),
            rs.getBigDecimal("amount"),
            rs.getString("cabin_class"),
            (Integer) rs.getObject("nights"),
            rs.getString("status"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")));

    private final JdbcTemplate jdbcTemplate;

    public OrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(TravelOrder order) {
        jdbcTemplate.update("""
                INSERT INTO travel_order
                    (id, approval_id, user_id, type, supplier, title, amount,
                     cabin_class, nights, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                order.id(), order.approvalId(), order.userId(), order.type(), order.supplier(),
                order.title(), order.amount(), order.cabinClass(), order.nights(), order.status());
    }

    public Optional<TravelOrder> findById(String id) {
        return jdbcTemplate.query("SELECT * FROM travel_order WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<TravelOrder> findByApprovalAndType(String approvalId, String type) {
        return jdbcTemplate.query(
                        "SELECT * FROM travel_order WHERE approval_id = ? AND type = ?",
                        MAPPER, approvalId, type)
                .stream().findFirst();
    }

    public List<TravelOrder> listByApproval(String approvalId) {
        return jdbcTemplate.query(
                "SELECT * FROM travel_order WHERE approval_id = ? ORDER BY created_at",
                MAPPER, approvalId);
    }

    public List<TravelOrder> listByUser(String userId) {
        return jdbcTemplate.query(
                "SELECT * FROM travel_order WHERE user_id = ? ORDER BY created_at DESC",
                MAPPER, userId);
    }

    public int updateBooked(String id, String supplierOrderNo) {
        return jdbcTemplate.update("""
                UPDATE travel_order
                SET status = ?, supplier_order_no = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, TravelOrder.BOOKED, supplierOrderNo, id);
    }

    public int updateStatus(String id, String status) {
        return jdbcTemplate.update(
                "UPDATE travel_order SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                status, id);
    }

    private static java.time.Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
