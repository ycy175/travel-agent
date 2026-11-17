package com.ycy.travelagent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ycy.travelagent.config.PromptProvider;
import com.ycy.travelagent.policy.PolicyService;
import com.ycy.travelagent.tools.TravelTools;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 差旅 Agent 确定性护栏回归评测。
 *
 * <p>评测理念：本项目的核心设计是"LLM 不可信，安全靠服务端护栏"。因此模型输出质量
 * 之外，更关键的是守住三类确定性契约——它们一旦退化，改 Prompt 就会静默引入越权风险：
 *
 * <ul>
 *   <li>PROMPT_RULE：系统提示词必须保留关键安全约束（防止改话术时误删）；</li>
 *   <li>SCHEMA_SAFETY：工具 Schema 不得把身份/职级暴露给模型填写（防越权）；</li>
 *   <li>POLICY_DECISION：差标决策边界符合预期（防规则回归）。</li>
 * </ul>
 *
 * <p>本评测不调用真实模型，CI 可稳定运行。模型真实输出质量的评测见 README「Agent 质量评测」。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agenteval;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "app.security.token-secret=test-secret",
        "agentscope.model.openai.api-key=test-key"
})
class AgentQualityEvalTest {

    @Autowired
    private PromptProvider promptProvider;

    @Autowired
    private PolicyService policyService;

    private final Toolkit toolkit = new Toolkit();
    private final ObjectMapper mapper = new ObjectMapper();

    private final List<String> report = new ArrayList<>();
    private int passed;
    private int total;

    AgentQualityEvalTest() {
        toolkit.registerTool(new TravelTools(null, null, null, null, null, null));
    }

    @Test
    void runEvalSuite() throws Exception {
        JsonNode suite;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("evals/agent-eval-cases.json")) {
            assertNotNull(in, "评测集文件缺失");
            suite = mapper.readTree(in);
        }

        String expectedVersion = suite.path("promptVersion").asText();
        report.add("=== 差旅 Agent 护栏回归评测 (prompt=" + expectedVersion + ") ===");

        for (JsonNode c : suite.path("cases")) {
            total++;
            String id = c.path("id").asText();
            String type = c.path("type").asText();
            String desc = c.path("description").asText();
            List<String> failures = new ArrayList<>();

            switch (type) {
                case "PROMPT_RULE" -> checkPrompt(c, failures);
                case "SCHEMA_SAFETY" -> checkSchema(c, failures);
                case "POLICY_DECISION" -> checkPolicy(c, failures);
                default -> failures.add("未知用例类型: " + type);
            }

            if (failures.isEmpty()) {
                passed++;
                report.add(String.format("[PASS] %-4s %s", id, desc));
            } else {
                report.add(String.format("[FAIL] %-4s %s -> %s", id, desc, String.join("; ", failures)));
            }
        }

        report.add("---");
        report.add(String.format("结果: %d/%d 通过", passed, total));
        System.out.println(String.join("\n", report));

        assertEquals(total, passed, "评测未全部通过:\n" + String.join("\n", report));
    }

    private void checkPrompt(JsonNode c, List<String> failures) {
        String prompt = promptProvider.systemPrompt();
        for (JsonNode anchor : c.path("anchors")) {
            if (!prompt.contains(anchor.asText())) {
                failures.add("Prompt 缺少锚点: " + anchor.asText());
            }
        }
    }

    private void checkSchema(JsonNode c, List<String> failures) {
        String toolName = c.path("tool").asText();
        ToolSchema schema = toolkit.getToolSchemas().stream()
                .filter(s -> toolName.equals(s.getName()))
                .findFirst()
                .orElse(null);
        if (schema == null) {
            failures.add("工具未注册: " + toolName);
            return;
        }
        Object props = schema.getParameters().get("properties");
        assertTrue(props instanceof Map, "schema properties 应为 Map");
        Set<String> fields = ((Map<?, ?>) props).keySet().stream()
                .map(String::valueOf).collect(Collectors.toSet());

        for (JsonNode f : c.path("mustNotContain")) {
            if (fields.contains(f.asText())) {
                failures.add("Schema 不应暴露字段: " + f.asText());
            }
        }
        for (JsonNode f : c.path("mustContain")) {
            if (!fields.contains(f.asText())) {
                failures.add("Schema 缺少业务字段: " + f.asText());
            }
        }
    }

    private void checkPolicy(JsonNode c, List<String> failures) {
        var violation = policyService.checkBooking(
                c.path("level").asText(),
                c.path("city").asText(),
                c.path("orderType").asText(),
                new BigDecimal(c.path("amount").asText()),
                c.path("cabin").isNull() ? null : c.path("cabin").asText(),
                c.path("nights").asInt());
        boolean actualViolation = violation.isPresent();
        boolean expected = c.path("expectViolation").asBoolean();
        if (actualViolation != expected) {
            failures.add("期望违规=" + expected + " 实际违规=" + actualViolation
                    + (violation.map(v -> " (" + v + ")").orElse("")));
        }
    }
}
