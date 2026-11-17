package com.ycy.travelagent.approval;

import com.ycy.travelagent.audit.AuditService;
import com.ycy.travelagent.auth.UserAccount;
import com.ycy.travelagent.auth.UserRepository;
import com.ycy.travelagent.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * 审批业务核心：
 * - Agent 通过工具创建 PENDING 单据（只起草，不写库外的任何真实订单）
 * - 本人可确认限额内的单据；超过限额必须由"同部门审批人"或管理员审批
 * - 所有状态迁移经过 ApprovalStatus.canTransitionTo + 乐观锁
 */
@Service
public class ApprovalService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    public record ApprovalView(
            String id, String userId, String sessionId, String itinerary, BigDecimal cost,
            String status, String rejectReason, String approverId,
            boolean canApprove, String approveHint, Instant createdAt, Instant updatedAt) {
    }

    private final ApprovalRepository approvalRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final BigDecimal selfApproveLimit;

    public ApprovalService(ApprovalRepository approvalRepository,
                           UserRepository userRepository,
                           AuditService auditService,
                           @Value("${app.approval.self-approve-limit:5000}") BigDecimal selfApproveLimit) {
        this.approvalRepository = approvalRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.selfApproveLimit = selfApproveLimit;
    }

    public Approval create(String userId, String sessionId, String itinerary, BigDecimal cost) {
        if (itinerary == null || itinerary.isBlank()) {
            throw ApiException.badRequest("行程摘要不能为空");
        }
        if (itinerary.length() > 1000) {
            throw ApiException.badRequest("行程摘要过长");
        }
        if (cost == null || cost.signum() <= 0) {
            throw ApiException.badRequest("预估费用必须大于 0");
        }
        if (cost.compareTo(new BigDecimal("1000000")) > 0) {
            throw ApiException.badRequest("预估费用超出合理范围");
        }
        String id = "APP-" + LocalDate.now().format(DAY) + "-"
                + String.format("%06X", RANDOM.nextInt(0x1000000));
        approvalRepository.insert(new Approval(id, userId, sessionId, itinerary.trim(), cost,
                ApprovalStatus.PENDING, null, null, null, 0, null, null));
        return approvalRepository.findById(id).orElseThrow();
    }

    public Approval get(String id, UserAccount actor) {
        Approval approval = approvalRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("审批单不存在"));
        if (!canView(approval, actor)) {
            throw ApiException.notFound("审批单不存在");
        }
        return approval;
    }

    public List<Approval> listBySession(String sessionId, UserAccount actor) {
        return approvalRepository.listBySession(sessionId).stream()
                .filter(a -> canView(a, actor))
                .toList();
    }

    /** 审批人待办：管理员看全部，部门审批人只看本部门。 */
    public List<Approval> inbox(UserAccount actor) {
        if (!actor.isApprover()) {
            throw ApiException.forbidden("只有审批人可以查看待办");
        }
        return approvalRepository.listPendingExcluding(actor.id()).stream()
                .filter(a -> canApprove(a, actor))
                .toList();
    }

    public List<Approval> listMine(String userId) {
        return approvalRepository.listByUser(userId);
    }

    /** 仅供 Agent 在单个会话内查询，避免把别的会话的审批单混进上下文。 */
    public List<Approval> listBySessionForUser(String sessionId, String userId) {
        return approvalRepository.listBySession(sessionId).stream()
                .filter(a -> a.userId().equals(userId))
                .toList();
    }

    public Approval approve(String id, UserAccount actor) {
        Approval approval = approvalRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("审批单不存在"));
        checkCanAct(approval, actor);
        applyTransition(approval, ApprovalStatus.APPROVED, actor.id(), null);
        auditService.record(actor.id(), approval.sessionId(), AuditService.ACTION_APPROVAL_APPROVE,
                approval.id(), "cost=" + approval.cost() + ", self=" + approval.userId().equals(actor.id()),
                true, null, null);
        return approvalRepository.findById(id).orElseThrow();
    }

    public Approval reject(String id, UserAccount actor, String reason) {
        Approval approval = approvalRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("审批单不存在"));
        checkCanAct(approval, actor);
        String safeReason = reason == null ? "" : reason.trim();
        if (safeReason.length() > 500) {
            throw ApiException.badRequest("驳回原因过长");
        }
        applyTransition(approval, ApprovalStatus.REJECTED, actor.id(), safeReason);
        auditService.record(actor.id(), approval.sessionId(), AuditService.ACTION_APPROVAL_REJECT,
                approval.id(), "reason=" + safeReason, true, null, null);
        return approvalRepository.findById(id).orElseThrow();
    }

    /** 订单创建成功后把审批单推进到 BOOKED；重复下单时不会重复调用。 */
    public Approval markBooked(String id) {
        Approval approval = approvalRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("审批单不存在"));
        if (approval.status() != ApprovalStatus.APPROVED) {
            throw ApiException.conflict("审批单当前状态为 " + approval.status() + "，不能标记为已预订");
        }
        applyTransition(approval, ApprovalStatus.BOOKED, approval.approverId(), approval.rejectReason());
        return approvalRepository.findById(id).orElseThrow();
    }

    public ApprovalView view(Approval approval, UserAccount actor) {
        boolean canApprove = "PENDING".equals(approval.status().name()) && canApprove(approval, actor);
        String hint;
        if (!"PENDING".equals(approval.status().name())) {
            hint = "当前状态：" + approval.status().name();
        } else if (canApprove) {
            hint = "可审批";
        } else if (approval.userId().equals(actor.id())
                && approval.cost().compareTo(selfApproveLimit) > 0) {
            hint = "金额超过本人确认额度（" + selfApproveLimit + " 元），需同部门审批人处理";
        } else {
            hint = "无审批权限（仅限同部门审批人或管理员）";
        }
        return new ApprovalView(approval.id(), approval.userId(), approval.sessionId(),
                approval.itinerary(), approval.cost(), approval.status().name(),
                approval.rejectReason(), approval.approverId(),
                canApprove, hint, approval.createdAt(), approval.updatedAt());
    }

    private boolean canView(Approval approval, UserAccount actor) {
        if (approval.userId().equals(actor.id()) || "ADMIN".equals(actor.role())) {
            return true;
        }
        return actor.isApprover() && sameDepartment(approval.userId(), actor);
    }

    private boolean canApprove(Approval approval, UserAccount actor) {
        if (!"PENDING".equals(approval.status().name())) {
            return false;
        }
        if (approval.userId().equals(actor.id())) {
            // 本人确认只允许在限额内；超过限额必须由他人审批，避免自己批自己
            return approval.cost().compareTo(selfApproveLimit) <= 0;
        }
        if ("ADMIN".equals(actor.role())) {
            return true;
        }
        return actor.isApprover() && sameDepartment(approval.userId(), actor);
    }

    private void checkCanAct(Approval approval, UserAccount actor) {
        if (approval.status() != ApprovalStatus.PENDING) {
            throw ApiException.conflict("审批单当前状态为 " + approval.status() + "，不可再次处理");
        }
        if (!canApprove(approval, actor)) {
            if (approval.userId().equals(actor.id())) {
                throw ApiException.forbidden("金额超过本人确认额度 " + selfApproveLimit
                        + " 元，需同部门审批人处理");
            }
            throw ApiException.forbidden("无权处理该审批单：仅限同部门审批人或管理员");
        }
    }

    private boolean sameDepartment(String requesterId, UserAccount actor) {
        if (actor.department() == null) {
            return false;
        }
        return userRepository.findById(requesterId)
                .map(UserAccount::department)
                .filter(Objects::nonNull)
                .map(dept -> dept.equals(actor.department()))
                .orElse(false);
    }

    private void applyTransition(Approval approval, ApprovalStatus target, String actorId, String reason) {
        if (!approval.status().canTransitionTo(target)) {
            throw ApiException.conflict("不允许从 " + approval.status() + " 迁移到 " + target);
        }
        int updated = approvalRepository.updateStatus(
                approval.id(), approval.version(), approval.status(), target, actorId, reason);
        if (updated == 0) {
            throw ApiException.conflict("审批单已被其他人处理，请刷新后重试");
        }
    }
}
