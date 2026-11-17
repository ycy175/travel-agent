package com.ycy.travelagent.order;

import com.ycy.travelagent.common.ApiException;
import org.springframework.stereotype.Component;

import java.util.List;

/** 按订单类型选择供应商实现。 */
@Component
public class SupplierRegistry {

    private final List<SupplierGateway> suppliers;

    public SupplierRegistry(List<SupplierGateway> suppliers) {
        this.suppliers = suppliers;
    }

    public SupplierGateway require(String type) {
        return suppliers.stream()
                .filter(s -> s.supportsType().equalsIgnoreCase(type))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("不支持的订单类型：" + type));
    }
}
