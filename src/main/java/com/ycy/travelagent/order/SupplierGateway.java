package com.ycy.travelagent.order;

import java.math.BigDecimal;

/**
 * 供应商适配层（防腐层）：业务只依赖这个接口，
 * 未来接携程/同程/企业协议价时新增实现即可，不用改 Agent 工具与订单逻辑。
 */
public interface SupplierGateway {

    String name();

    /** FLIGHT / HOTEL */
    String supportsType();

    Result book(Command command);

    record Command(String orderId, String title, BigDecimal amount, String city, String travelerId) {
    }

    record Result(boolean success, String supplierOrderNo, String message) {
    }
}
