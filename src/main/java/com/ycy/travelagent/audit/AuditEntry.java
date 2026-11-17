package com.ycy.travelagent.audit;

import java.time.Instant;

public record AuditEntry(
        long id,
        String userId,
        String sessionId,
        String action,
        String resource,
        String detail,
        boolean success,
        Integer durationMs,
        String ip,
        Instant createdAt) {
}
