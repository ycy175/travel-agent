package com.ycy.travelagent.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 审计入口。写入失败只记日志、不影响主流程——
 * 审计是旁路，不能因为日志库抖动让用户下单失败。
 */
@Service
public class AuditService {

    public static final String ACTION_TOOL_CALL = "TOOL_CALL";
    public static final String ACTION_APPROVAL_APPROVE = "APPROVAL_APPROVE";
    public static final String ACTION_APPROVAL_REJECT = "APPROVAL_REJECT";
    public static final String ACTION_ORDER_BOOK = "ORDER_BOOK";
    public static final String ACTION_LOGIN = "LOGIN";
    public static final String ACTION_CHAT = "CHAT";

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int MAX_DETAIL = 4000;

    private final AuditRepository auditRepository;

    public AuditService(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    public void record(String userId, String sessionId, String action, String resource,
                       String detail, boolean success, Integer durationMs, String ip) {
        try {
            auditRepository.insert(userId, sessionId, action, resource,
                    truncate(detail), success, durationMs, ip);
        } catch (Exception e) {
            log.warn("审计写入失败 action={} resource={}: {}", action, resource, e.getMessage());
        }
    }

    public List<AuditEntry> recent(int limit) {
        return auditRepository.list(clamp(limit));
    }

    public List<AuditEntry> recentForUser(String userId, int limit) {
        return auditRepository.listByUser(userId, clamp(limit));
    }

    private int clamp(int limit) {
        return Math.max(1, Math.min(limit, 500));
    }

    private String truncate(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() <= MAX_DETAIL ? detail : detail.substring(0, MAX_DETAIL);
    }
}
