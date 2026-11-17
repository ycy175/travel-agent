package com.ycy.travelagent.tools;

import com.ycy.travelagent.approval.Approval;
import com.ycy.travelagent.approval.ApprovalService;
import com.ycy.travelagent.audit.AuditService;
import com.ycy.travelagent.auth.UserAccount;
import com.ycy.travelagent.auth.UserRepository;
import com.ycy.travelagent.common.ApiException;
import com.ycy.travelagent.idempotency.IdempotencyService;
import com.ycy.travelagent.order.OrderService;
import com.ycy.travelagent.order.TravelOrder;
import com.ycy.travelagent.policy.PolicyService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

/**
 * Agent 的全部业务工具。
 * 身份、职级、审批状态均由服务端注入/校验，模型只能提供业务参数。
 * 每次工具调用都会写审计日志（参数 + 结果 + 耗时）。
 */
@Component
public class TravelTools {

    private static final Logger log = LoggerFactory.getLogger(TravelTools.class);

    private final IdempotencyService idempotencyService;
    private final ApprovalService approvalService;
    private final PolicyService policyService;
    private final OrderService orderService;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public TravelTools(IdempotencyService idempotencyService,
                       ApprovalService approvalService,
                       PolicyService policyService,
                       OrderService orderService,
                       UserRepository userRepository,
                       AuditService auditService) {
        this.idempotencyService = idempotencyService;
        this.approvalService = approvalService;
        this.policyService = policyService;
        this.orderService = orderService;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Tool(name = "query_travel_policy",
            description = "查询当前登录员工的差旅标准（按员工职级和目的城市）。只读操作。")
    public String queryPolicy(
            @ToolParam(name = "city", description = "目的城市，例如 上海") String city,
            RuntimeContext ctx) {
        return audited(ctx, "query_travel_policy", "city=" + city, () -> {
            UserAccount user = currentUser(ctx);
            if (user == null) {
                return "无法查询：未获取到登录用户。";
            }
            return policyService.describe(user.employeeLevel(), city);
        });
    }

    @Tool(name = "submit_travel_approval",
            description = "提交差旅审批单，生成待人工确认的 PENDING 记录。审批通过前不得进行任何预订。")
    public String submitApproval(
            @ToolParam(name = "itinerary", description = "行程摘要，例如：北京-上海 2026-11-18 往返，经济舱") String itinerary,
            @ToolParam(name = "cost", description = "预估总费用，单位元，只填数字") String cost,
            RuntimeContext ctx) {
        return audited(ctx, "submit_travel_approval", "cost=" + cost + ", itinerary=" + itinerary, () -> {
            String userId = ctx.getUserId();
            String sessionId = ctx.getSessionId();
            if (userId == null || sessionId == null) {
                return "无法提交审批：缺少用户或会话上下文。";
            }
            BigDecimal amount;
            try {
                amount = new BigDecimal(cost == null ? "" : cost.replace(",", "").replace("¥", "").trim());
            } catch (NumberFormatException e) {
                return "费用格式不正确，请只填写数字，例如 3500。";
            }
            if (amount.signum() <= 0) {
                return "费用必须大于 0，请确认后再提交。";
            }

            String key = DigestUtils.md5DigestAsHex(
                    (userId + "|" + sessionId + "|" + itinerary + "|" + amount.toPlainString())
                            .getBytes(StandardCharsets.UTF_8));

            String result = idempotencyService.execute(key, () -> {
                Approval approval = approvalService.create(userId, sessionId, itinerary, amount);
                log.info(">>> [Tool] 创建审批单 {}，用户 {}，金额 {}", approval.id(), userId, amount);
                return "{\"approvalId\":\"" + approval.id() + "\",\"status\":\"PENDING\","
                        + "\"cost\":" + amount.toPlainString() + "}";
            });

            if (!result.startsWith("{")) {
                return result;
            }
            return result + "\n审批单已生成，状态 PENDING。请把审批单号告知用户，等待人工确认后再继续。";
        });
    }

    @Tool(name = "book_travel",
            description = "为已通过的审批单创建预订订单（机票或酒店）。仅当审批单状态为 APPROVED 或 BOOKED 时成功；会做差标校验。")
    public String bookTravel(
            @ToolParam(name = "approval_id", description = "审批单号，例如 APP-20261114-0A8DBC") String approvalId,
            @ToolParam(name = "type", description = "订单类型：FLIGHT 或 HOTEL") String type,
            @ToolParam(name = "title", description = "订单标题，例如 北京-上海 CA1234 经济舱 / 上海某某酒店") String title,
            @ToolParam(name = "amount", description = "订单金额，单位元，只填数字") String amount,
            @ToolParam(name = "city", description = "目的城市，用于差标校验") String city,
            @ToolParam(name = "cabin_class", description = "舱位：ECONOMY/PREMIUM/BUSINESS/FIRST，酒店订单可留空") String cabinClass,
            @ToolParam(name = "nights", description = "住宿晚数，仅酒店订单需要") String nights,
            RuntimeContext ctx) {

        String detail = "approval=" + approvalId + ", type=" + type + ", amount=" + amount
                + ", city=" + city + ", cabin=" + cabinClass + ", nights=" + nights;
        return audited(ctx, "book_travel", detail, () -> {
            UserAccount user = currentUser(ctx);
            if (user == null) {
                return "无法下单：未获取到登录用户。";
            }
            BigDecimal amountValue;
            try {
                amountValue = new BigDecimal(amount == null ? "" : amount.replace(",", "").replace("¥", "").trim());
            } catch (NumberFormatException e) {
                return "金额格式不正确，请只填写数字。";
            }
            Integer nightsValue = null;
            if (nights != null && !nights.isBlank()) {
                try {
                    nightsValue = Integer.parseInt(nights.trim());
                } catch (NumberFormatException e) {
                    return "住宿晚数必须是整数。";
                }
            }

            try {
                TravelOrder order = orderService.book(user, approvalId,
                        new OrderService.BookingRequest(type, title, amountValue, cabinClass, nightsValue, city));
                log.info(">>> [Tool] 下单成功 order={} status={}", order.id(), order.status());
                return "{\"orderId\":\"" + order.id() + "\",\"status\":\"" + order.status()
                        + "\",\"supplierOrderNo\":\"" + safe(order.supplierOrderNo()) + "\","
                        + "\"amount\":" + order.amount().toPlainString() + "}\n"
                        + "下单成功，请把订单号与供应商单号告知用户。";
            } catch (ApiException e) {
                log.info(">>> [Tool] 下单被拒绝: {}", e.getMessage());
                return "下单失败：" + e.getMessage();
            }
        });
    }

    @Tool(name = "query_my_approvals", description = "查询当前会话中当前用户的审批单及状态。只读操作。")
    public String queryMyApprovals(RuntimeContext ctx) {
        return audited(ctx, "query_my_approvals", "", () -> {
            List<Approval> approvals = approvalService.listBySessionForUser(ctx.getSessionId(), ctx.getUserId());
            if (approvals.isEmpty()) {
                return "当前会话暂无审批单。";
            }
            StringBuilder sb = new StringBuilder("当前会话的审批单：\n");
            approvals.stream().limit(10).forEach(a -> sb.append("- ")
                    .append(a.id()).append(" | ").append(a.status())
                    .append(" | ").append(a.cost()).append(" 元 | ")
                    .append(a.itinerary()).append("\n"));
            return sb.toString();
        });
    }

    @Tool(name = "query_my_orders", description = "查询当前用户名下的订单及状态。只读操作。")
    public String queryMyOrders(RuntimeContext ctx) {
        return audited(ctx, "query_my_orders", "", () -> {
            List<TravelOrder> orders = orderService.listMine(ctx.getUserId());
            if (orders.isEmpty()) {
                return "当前用户名下暂无订单。";
            }
            StringBuilder sb = new StringBuilder("当前用户最近的订单：\n");
            orders.stream().limit(10).forEach(o -> sb.append("- ")
                    .append(o.id()).append(" | ").append(o.type()).append(" | ").append(o.status())
                    .append(" | ").append(o.amount()).append(" 元 | ")
                    .append("供应商单号 ").append(safe(o.supplierOrderNo())).append("\n"));
            return sb.toString();
        });
    }

    /** 统一审计包装：记录参数、结果、耗时；异常也留痕。 */
    private String audited(RuntimeContext ctx, String tool, String detail, Supplier<String> body) {
        long start = System.currentTimeMillis();
        try {
            String result = body.get();
            auditService.record(ctx.getUserId(), ctx.getSessionId(), AuditService.ACTION_TOOL_CALL,
                    tool, detail + " => " + result, true,
                    (int) (System.currentTimeMillis() - start), null);
            return result;
        } catch (RuntimeException e) {
            auditService.record(ctx.getUserId(), ctx.getSessionId(), AuditService.ACTION_TOOL_CALL,
                    tool, detail + " => ERROR " + e.getMessage(), false,
                    (int) (System.currentTimeMillis() - start), null);
            throw e;
        }
    }

    private UserAccount currentUser(RuntimeContext ctx) {
        String userId = ctx.getUserId();
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).orElse(null);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
