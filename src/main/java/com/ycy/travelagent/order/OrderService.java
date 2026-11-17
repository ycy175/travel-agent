package com.ycy.travelagent.order;

import com.ycy.travelagent.approval.Approval;
import com.ycy.travelagent.approval.ApprovalRepository;
import com.ycy.travelagent.approval.ApprovalService;
import com.ycy.travelagent.approval.ApprovalStatus;
import com.ycy.travelagent.audit.AuditService;
import com.ycy.travelagent.auth.UserAccount;
import com.ycy.travelagent.common.ApiException;
import com.ycy.travelagent.policy.PolicyService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 下单用例：
 * 审批通过 -> 差标校验 -> 创建订单(CREATED) -> 调供应商 -> BOOKED/FAILED。
 * 唯一索引 (approval_id, type) 保证重复调用不会重复下单。
 */
@Service
public class OrderService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    public record BookingRequest(String type, String title, BigDecimal amount,
                                 String cabinClass, Integer nights, String city) {
    }

    private final OrderRepository orderRepository;
    private final ApprovalRepository approvalRepository;
    private final ApprovalService approvalService;
    private final SupplierRegistry supplierRegistry;
    private final PolicyService policyService;
    private final AuditService auditService;

    public OrderService(OrderRepository orderRepository,
                        ApprovalRepository approvalRepository,
                        ApprovalService approvalService,
                        SupplierRegistry supplierRegistry,
                        PolicyService policyService,
                        AuditService auditService) {
        this.orderRepository = orderRepository;
        this.approvalRepository = approvalRepository;
        this.approvalService = approvalService;
        this.supplierRegistry = supplierRegistry;
        this.policyService = policyService;
        this.auditService = auditService;
    }

    /**
     * noRollbackFor：供应商失败属于"预期内业务失败"，需要把 FAILED 订单和失败审计提交下来；
     * 其它 RuntimeException 仍然回滚。
     */
    @Transactional(noRollbackFor = ApiException.class)
    public TravelOrder book(UserAccount user, String approvalId, BookingRequest request) {
        String type = normalizeType(request.type());
        String title = request.title() == null ? "" : request.title().trim();
        if (title.isEmpty()) {
            throw ApiException.badRequest("订单标题不能为空");
        }
        if (title.length() > 255) {
            throw ApiException.badRequest("订单标题过长");
        }
        BigDecimal amount = request.amount();
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.badRequest("订单金额必须大于 0");
        }

        Approval approval = approvalRepository.findById(approvalId)
                .orElseThrow(() -> ApiException.notFound("审批单不存在"));
        if (!approval.userId().equals(user.id())) {
            throw ApiException.forbidden("只能为本人审批单下单");
        }
        if (approval.status() != ApprovalStatus.APPROVED && approval.status() != ApprovalStatus.BOOKED) {
            throw ApiException.conflict("审批单当前状态为 " + approval.status() + "，未通过审批不能下单");
        }

        int nights = request.nights() == null ? 1 : Math.max(request.nights(), 1);
        policyService.checkBooking(user.employeeLevel(), request.city(), type, amount,
                        request.cabinClass(), nights)
                .ifPresent(reason -> {
                    throw ApiException.conflict("差标校验未通过：" + reason);
                });

        // 累计订单金额不能超过审批金额（失败/取消的订单不计入）
        BigDecimal committed = orderRepository.listByApproval(approvalId).stream()
                .filter(o -> !TravelOrder.FAILED.equals(o.status())
                        && !TravelOrder.CANCELLED.equals(o.status()))
                .map(TravelOrder::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (committed.add(amount).compareTo(approval.cost()) > 0) {
            throw ApiException.conflict("订单累计金额 " + committed.add(amount).toPlainString()
                    + " 元超过审批金额 " + approval.cost().toPlainString() + " 元");
        }

        // 幂等：同一审批单同类型只允许一单；FAILED 的订单允许重试
        var existing = orderRepository.findByApprovalAndType(approvalId, type);
        if (existing.isPresent()) {
            TravelOrder order = existing.get();
            if (TravelOrder.BOOKED.equals(order.status())) {
                return order;
            }
            if (TravelOrder.FAILED.equals(order.status())) {
                orderRepository.updateStatus(order.id(), TravelOrder.CREATED);
                return executeBooking(order, user, approval, request.city());
            }
            return order;
        }

        SupplierGateway supplier = supplierRegistry.require(type);
        String orderId = "ORD-" + LocalDate.now().format(DAY) + "-"
                + String.format("%06X", RANDOM.nextInt(0x1000000));
        TravelOrder order = new TravelOrder(orderId, approvalId, user.id(), type, supplier.name(),
                null, title, amount, request.cabinClass(), nights, TravelOrder.CREATED, null, null);
        try {
            orderRepository.insert(order);
        } catch (DuplicateKeyException e) {
            // 并发下另一个请求先插入，直接返回已有订单
            return orderRepository.findByApprovalAndType(approvalId, type).orElseThrow();
        }
        return executeBooking(order, user, approval, request.city());
    }

    private TravelOrder executeBooking(TravelOrder order, UserAccount user, Approval approval, String city) {
        SupplierGateway supplier = supplierRegistry.require(order.type());
        SupplierGateway.Result result = supplier.book(new SupplierGateway.Command(
                order.id(), order.title(), order.amount(), city, user.id()));

        if (!result.success()) {
            orderRepository.updateStatus(order.id(), TravelOrder.FAILED);
            auditService.record(user.id(), approval.sessionId(), AuditService.ACTION_ORDER_BOOK,
                    order.id(), "type=" + order.type() + ", amount=" + order.amount()
                            + ", supplier=" + supplier.name() + ", fail=" + result.message(),
                    false, null, null);
            throw new ApiException(502, "供应商下单失败：" + result.message());
        }

        orderRepository.updateBooked(order.id(), result.supplierOrderNo());
        if (approval.status() == ApprovalStatus.APPROVED) {
            approvalService.markBooked(approval.id());
        }
        auditService.record(user.id(), approval.sessionId(), AuditService.ACTION_ORDER_BOOK,
                order.id(), "type=" + order.type() + ", amount=" + order.amount()
                        + ", supplierOrderNo=" + result.supplierOrderNo(), true, null, null);
        return orderRepository.findById(order.id()).orElseThrow();
    }

    public List<TravelOrder> listByApproval(String approvalId, UserAccount actor) {
        Approval approval = approvalRepository.findById(approvalId)
                .orElseThrow(() -> ApiException.notFound("审批单不存在"));
        if (!approval.userId().equals(actor.id()) && !actor.isApprover()) {
            throw ApiException.notFound("审批单不存在");
        }
        return orderRepository.listByApproval(approvalId);
    }

    public List<TravelOrder> listMine(String userId) {
        return orderRepository.listByUser(userId);
    }

    private String normalizeType(String type) {
        String value = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        if (!"FLIGHT".equals(value) && !"HOTEL".equals(value)) {
            throw ApiException.badRequest("订单类型只能是 FLIGHT 或 HOTEL");
        }
        return value;
    }
}
