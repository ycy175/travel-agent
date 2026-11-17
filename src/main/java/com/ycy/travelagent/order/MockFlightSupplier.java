package com.ycy.travelagent.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.security.SecureRandom;

/**
 * 机票供应商 Mock。用确定性规则模拟失败场景，方便演示补偿与幂等。
 */
@Component
public class MockFlightSupplier implements SupplierGateway {

    private static final Logger log = LoggerFactory.getLogger(MockFlightSupplier.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final BigDecimal SINGLE_LIMIT = new BigDecimal("10000");

    @Override
    public String name() {
        return "MOCK-AIR";
    }

    @Override
    public String supportsType() {
        return "FLIGHT";
    }

    @Override
    public Result book(Command command) {
        if (command.amount().compareTo(SINGLE_LIMIT) > 0) {
            return new Result(false, null, "供应商单笔限额 10000 元，当前金额 " + command.amount());
        }
        if (command.title() != null && command.title().contains("售罄")) {
            return new Result(false, null, "该航班已售罄，请更换航班");
        }
        String no = "FL" + System.currentTimeMillis() % 100000000 + String.format("%03d", RANDOM.nextInt(1000));
        log.info(">>> [MockFlight] 出票成功 order={} supplierOrderNo={}", command.orderId(), no);
        return new Result(true, no, "出票成功");
    }
}
