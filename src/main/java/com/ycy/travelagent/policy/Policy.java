package com.ycy.travelagent.policy;

import java.math.BigDecimal;

/**
 * 一条差旅政策：职级 + 城市（'*' 为默认）决定舱位、酒店上限、补贴与提前天数。
 */
public record Policy(
        String id,
        String employeeLevel,
        String city,
        String flightClass,
        BigDecimal hotelLimit,
        BigDecimal dailyAllowance,
        int advanceDays) {
}
