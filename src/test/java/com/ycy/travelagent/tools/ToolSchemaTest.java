package com.ycy.travelagent.tools;

import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 防止 RuntimeContext 这类框架注入参数泄漏进工具 JSON Schema：
 * 一旦泄漏，模型会试图自己填写 userId，造成越权风险。
 */
class ToolSchemaTest {

    private final Toolkit toolkit = new Toolkit();

    ToolSchemaTest() {
        toolkit.registerTool(new TravelTools(null, null, null, null, null, null));
    }

    @Test
    void submitApprovalSchemaContainsOnlyBusinessFields() {
        assertEquals(Set.of("itinerary", "cost"), propertiesOf("submit_travel_approval"));
    }

    @Test
    void bookTravelSchemaContainsOnlyBusinessFields() {
        assertEquals(Set.of("approval_id", "type", "title", "amount", "city", "cabin_class", "nights"),
                propertiesOf("book_travel"));
    }

    @Test
    void policyQueryDoesNotExposeLevelParameter() {
        assertEquals(Set.of("city"), propertiesOf("query_travel_policy"),
                "职级必须由服务端按登录用户注入，不能让模型填写");
    }

    private Set<String> propertiesOf(String toolName) {
        ToolSchema schema = toolkit.getToolSchemas().stream()
                .filter(s -> toolName.equals(s.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(toolName + " 未注册"));

        Object properties = schema.getParameters().get("properties");
        assertNotNull(properties, toolName + " 缺少 properties");
        assertTrue(properties instanceof Map, toolName + " properties 应为 Map");

        return ((Map<?, ?>) properties).keySet().stream()
                .map(String::valueOf)
                .collect(Collectors.toSet());
    }
}
