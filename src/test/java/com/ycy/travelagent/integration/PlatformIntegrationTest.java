package com.ycy.travelagent.integration;

import com.ycy.travelagent.approval.Approval;
import com.ycy.travelagent.approval.ApprovalService;
import com.ycy.travelagent.audit.AuditService;
import com.ycy.travelagent.auth.UserAccount;
import com.ycy.travelagent.auth.UserRepository;
import com.ycy.travelagent.chat.ChatRepository;
import com.ycy.travelagent.common.ApiException;
import com.ycy.travelagent.order.OrderService;
import com.ycy.travelagent.order.TravelOrder;
import com.ycy.travelagent.policy.PolicyService;
import com.ycy.travelagent.usage.UsageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核心业务规则的集成测试：差标、审批状态机、RBAC、订单幂等、审计、预算。
 * 使用内存库，不污染开发数据。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:phase3test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "app.security.token-secret=test-secret",
        "agentscope.model.openai.api-key=test-key",
        "app.quota.monthly-tokens=100000"
})
class PlatformIntegrationTest {

    @Autowired
    private ApprovalService approvalService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PolicyService policyService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private AuditService auditService;
    @Autowired
    private UsageService usageService;
    @Autowired
    private ChatRepository chatRepository;

    private UserAccount zhangsan;
    private UserAccount lisi;
    private UserAccount wangwu;
    private UserAccount admin;

    @BeforeEach
    void setUp() {
        zhangsan = userRepository.findByUsername("zhangsan").orElseThrow();
        lisi = userRepository.findByUsername("lisi").orElseThrow();
        wangwu = userRepository.findByUsername("wangwu").orElseThrow();
        admin = userRepository.findByUsername("admin").orElseThrow();
    }

    @Test
    void cityPolicyOverridesDefault() {
        var shanghai = policyService.resolve("P6", "上海").orElseThrow();
        assertEquals("POL-P6-SH", shanghai.id());
        assertEquals(new BigDecimal("600.00"), shanghai.hotelLimit());

        var defaultPolicy = policyService.resolve("P6", "广州").orElseThrow();
        assertEquals("POL-P6-*", defaultPolicy.id());
        assertEquals(new BigDecimal("500.00"), defaultPolicy.hotelLimit());
    }

    @Test
    void cabinMustNotExceedLevel() {
        assertTrue(policyService.checkBooking("P6", "上海", "FLIGHT",
                new BigDecimal("1280"), "BUSINESS", 0).isPresent());
        assertTrue(policyService.checkBooking("P6", "上海", "FLIGHT",
                new BigDecimal("1280"), "ECONOMY", 0).isEmpty());
    }

    @Test
    void hotelLimitUsesPerNightPrice() {
        // 2 晚 1300 => 650/晚，超过 600
        assertTrue(policyService.checkBooking("P6", "上海", "HOTEL",
                new BigDecimal("1300"), null, 2).isPresent());
        // 2 晚 1200 => 600/晚，等于上限，允许
        assertTrue(policyService.checkBooking("P6", "上海", "HOTEL",
                new BigDecimal("1200"), null, 2).isEmpty());
    }

    @Test
    void ownerCanApproveWithinLimit() {
        Approval approval = approvalService.create(zhangsan.id(), "sess_a1", "上海 3 天", new BigDecimal("3500"));
        Approval approved = approvalService.approve(approval.id(), zhangsan);
        assertEquals("APPROVED", approved.status().name());
    }

    @Test
    void ownerCannotApproveOverLimit() {
        Approval approval = approvalService.create(zhangsan.id(), "sess_a2", "上海 5 天", new BigDecimal("6000"));
        ApiException ex = assertThrows(ApiException.class,
                () -> approvalService.approve(approval.id(), zhangsan));
        assertEquals(403, ex.getStatus());
        assertTrue(ex.getMessage().contains("额度"));
    }

    @Test
    void approverCannotCrossDepartment() {
        Approval approval = approvalService.create(lisi.id(), "sess_a3", "市场活动", new BigDecimal("6000"));
        ApiException ex = assertThrows(ApiException.class,
                () -> approvalService.approve(approval.id(), wangwu));
        assertEquals(403, ex.getStatus());
    }

    @Test
    void sameDepartmentApproverCanApprove() {
        Approval approval = approvalService.create(zhangsan.id(), "sess_a4", "研发出差", new BigDecimal("6000"));
        Approval approved = approvalService.approve(approval.id(), wangwu);
        assertEquals("APPROVED", approved.status().name());
        assertEquals(wangwu.id(), approved.approverId());
    }

    @Test
    void adminCanApproveAnyDepartment() {
        Approval approval = approvalService.create(lisi.id(), "sess_a5", "市场活动", new BigDecimal("6000"));
        Approval approved = approvalService.approve(approval.id(), admin);
        assertEquals("APPROVED", approved.status().name());
    }

    @Test
    void cannotApproveTwice() {
        Approval approval = approvalService.create(zhangsan.id(), "sess_a6", "重复审批", new BigDecimal("1000"));
        approvalService.approve(approval.id(), zhangsan);
        ApiException ex = assertThrows(ApiException.class,
                () -> approvalService.approve(approval.id(), zhangsan));
        assertEquals(409, ex.getStatus());
    }

    @Test
    void bookingRequiresApprovedApproval() {
        Approval pending = approvalService.create(zhangsan.id(), "sess_o1", "未审批", new BigDecimal("3000"));
        ApiException ex = assertThrows(ApiException.class,
                () -> orderService.book(zhangsan, pending.id(),
                        new OrderService.BookingRequest("FLIGHT", "CA1234 经济舱",
                                new BigDecimal("1200"), "ECONOMY", 1, "上海")));
        assertEquals(409, ex.getStatus());
    }

    @Test
    void bookingIsIdempotent() {
        Approval approval = approvalService.create(zhangsan.id(), "sess_o2", "上海往返", new BigDecimal("3500"));
        approvalService.approve(approval.id(), zhangsan);

        OrderService.BookingRequest request = new OrderService.BookingRequest(
                "FLIGHT", "CA1234 经济舱", new BigDecimal("1200"), "ECONOMY", 1, "上海");
        TravelOrder first = orderService.book(zhangsan, approval.id(), request);
        TravelOrder second = orderService.book(zhangsan, approval.id(), request);

        assertEquals(first.id(), second.id());
        List<TravelOrder> orders = orderService.listByApproval(approval.id(), zhangsan);
        assertEquals(1, orders.size());
        assertEquals(TravelOrder.BOOKED, orders.get(0).status());
        assertEquals("BOOKED", approvalService.get(approval.id(), zhangsan).status().name());
    }

    @Test
    void hotelOverPolicyIsRejected() {
        Approval approval = approvalService.create(zhangsan.id(), "sess_o3", "上海住宿", new BigDecimal("5000"));
        approvalService.approve(approval.id(), zhangsan);

        ApiException ex = assertThrows(ApiException.class,
                () -> orderService.book(zhangsan, approval.id(),
                        new OrderService.BookingRequest("HOTEL", "外滩酒店 2 晚",
                                new BigDecimal("3000"), null, 2, "上海")));
        assertEquals(409, ex.getStatus());
        assertTrue(ex.getMessage().contains("差标"));
        assertTrue(orderService.listByApproval(approval.id(), zhangsan).isEmpty());
    }

    @Test
    void cannotBookForOthersApproval() {
        Approval approval = approvalService.create(lisi.id(), "sess_o4", "市场出差", new BigDecimal("5000"));
        approvalService.approve(approval.id(), lisi);
        ApiException ex = assertThrows(ApiException.class,
                () -> orderService.book(zhangsan, approval.id(),
                        new OrderService.BookingRequest("FLIGHT", "MU5678 经济舱",
                                new BigDecimal("900"), "ECONOMY", 1, "上海")));
        assertEquals(403, ex.getStatus());
    }

    @Test
    void orderAmountCannotExceedApproval() {
        Approval approval = approvalService.create(zhangsan.id(), "sess_o5", "短途", new BigDecimal("1000"));
        approvalService.approve(approval.id(), zhangsan);
        ApiException ex = assertThrows(ApiException.class,
                () -> orderService.book(zhangsan, approval.id(),
                        new OrderService.BookingRequest("FLIGHT", "CA1234 经济舱",
                                new BigDecimal("1200"), "ECONOMY", 1, "上海")));
        assertEquals(409, ex.getStatus());
        assertTrue(ex.getMessage().contains("超过审批金额"));
    }

    @Test
    void supplierFailureLeavesFailedOrderForTroubleshooting() {
        Approval approval = approvalService.create(zhangsan.id(), "sess_o6", "大额机票", new BigDecimal("15000"));
        approvalService.approve(approval.id(), wangwu);

        ApiException ex = assertThrows(ApiException.class,
                () -> orderService.book(zhangsan, approval.id(),
                        new OrderService.BookingRequest("FLIGHT", "CA1234 经济舱",
                                new BigDecimal("12000"), "ECONOMY", 1, "上海")));
        assertEquals(502, ex.getStatus());

        List<TravelOrder> orders = orderService.listByApproval(approval.id(), zhangsan);
        assertEquals(1, orders.size());
        assertEquals(TravelOrder.FAILED, orders.get(0).status());
        assertTrue(auditService.recentForUser(zhangsan.id(), 50).stream()
                .anyMatch(e -> AuditService.ACTION_ORDER_BOOK.equals(e.action()) && !e.success()));
    }

    @Test
    void approvalWritesAuditLog() {
        Approval approval = approvalService.create(zhangsan.id(), "sess_audit", "审计测试", new BigDecimal("800"));
        approvalService.approve(approval.id(), zhangsan);

        boolean found = auditService.recentForUser(zhangsan.id(), 50).stream()
                .anyMatch(e -> AuditService.ACTION_APPROVAL_APPROVE.equals(e.action())
                        && approval.id().equals(e.resource()));
        assertTrue(found, "审批动作应写入审计日志");
    }

    @Test
    void usageSumsCurrentMonthTokens() {
        long before = usageService.usedTokensThisMonth(zhangsan.id());
        chatRepository.createSession("sess_usage_t", zhangsan.id(), "用量测试");
        chatRepository.appendMessage("sess_usage_t", "assistant", "hi", 100, 50, new BigDecimal("0.001"));

        long after = usageService.usedTokensThisMonth(zhangsan.id());
        assertEquals(before + 150, after);
        assertFalse(usageService.current(zhangsan.id()).remaining() < 0);
    }
}
