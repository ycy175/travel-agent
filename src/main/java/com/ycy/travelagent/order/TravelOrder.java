package com.ycy.travelagent.order;

import java.math.BigDecimal;
import java.time.Instant;

public record TravelOrder(
        String id,
        String approvalId,
        String userId,
        String type,
        String supplier,
        String supplierOrderNo,
        String title,
        BigDecimal amount,
        String cabinClass,
        Integer nights,
        String status,
        Instant createdAt,
        Instant updatedAt) {

    public static final String CREATED = "CREATED";
    public static final String BOOKED = "BOOKED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
}
