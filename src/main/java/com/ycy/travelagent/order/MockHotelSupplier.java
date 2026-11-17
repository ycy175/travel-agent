package com.ycy.travelagent.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.security.SecureRandom;

@Component
public class MockHotelSupplier implements SupplierGateway {

    private static final Logger log = LoggerFactory.getLogger(MockHotelSupplier.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final BigDecimal SINGLE_LIMIT = new BigDecimal("15000");

    @Override
    public String name() {
        return "MOCK-HOTEL";
    }

    @Override
    public String supportsType() {
        return "HOTEL";
    }

    @Override
    public Result book(Command command) {
        if (command.amount().compareTo(SINGLE_LIMIT) > 0) {
            return new Result(false, null, "酒店单笔限额 15000 元，当前金额 " + command.amount());
        }
        if (command.title() != null && command.title().contains("满房")) {
            return new Result(false, null, "酒店已满房，请更换酒店");
        }
        String no = "HT" + System.currentTimeMillis() % 100000000 + String.format("%03d", RANDOM.nextInt(1000));
        log.info(">>> [MockHotel] 预订成功 order={} supplierOrderNo={}", command.orderId(), no);
        return new Result(true, no, "预订成功");
    }
}
