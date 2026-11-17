package com.ycy.travelagent.approval;

/**
 * 审批单状态机。集中定义合法迁移，避免业务代码里散落 if/else。
 *
 * PENDING  -> APPROVED / REJECTED / CANCELLED
 * APPROVED -> BOOKED / CANCELLED
 */
public enum ApprovalStatus {

    PENDING, APPROVED, REJECTED, BOOKED, CANCELLED;

    public boolean canTransitionTo(ApprovalStatus next) {
        return switch (this) {
            case PENDING -> next == APPROVED || next == REJECTED || next == CANCELLED;
            case APPROVED -> next == BOOKED || next == CANCELLED;
            case REJECTED, BOOKED, CANCELLED -> false;
        };
    }

    public boolean isFinal() {
        return this == REJECTED || this == CANCELLED;
    }
}
