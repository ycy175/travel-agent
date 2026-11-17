package com.ycy.travelagent.approval;

import java.math.BigDecimal;
import java.time.Instant;

public record Approval(
        String id,
        String userId,
        String sessionId,
        String itinerary,
        BigDecimal cost,
        ApprovalStatus status,
        String rejectReason,
        String approverId,
        Instant approvedAt,
        int version,
        Instant createdAt,
        Instant updatedAt) {
}
