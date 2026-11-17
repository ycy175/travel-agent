package com.ycy.travelagent.config;

import com.ycy.travelagent.tools.TravelTools;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.state.JdbcAgentStateStore;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.file.Path;

@Configuration
public class AgentConfig {

    @Value("${agentscope.model.openai.api-key}")
    private String apiKey;

    @Value("${agentscope.model.openai.base-url}")
    private String baseUrl;

    @Value("${agentscope.model.openai.model-name}")
    private String modelName;

    private final TravelTools travelTools;
    private final DataSource dataSource;

    public AgentConfig(TravelTools travelTools, DataSource dataSource) {
        this.travelTools = travelTools;
        this.dataSource = dataSource;
    }

    @Bean
    public HarnessAgent.Builder harnessAgentBuilder() throws Exception {
        Path workspace = WorkspaceInitializer.init();

        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .stream(true)
                .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(travelTools);

        JdbcAgentStateStore stateStore = new JdbcAgentStateStore(
                dataSource,
                AbstractJdbcDialect.from(dataSource).build(),
                true);

        // MCP 子进程在应用启动时只拉起一次，所有请求复用同一个客户端
        String javaHome = System.getProperty("java.home");
        String classpath = System.getProperty("java.class.path");
        McpClientWrapper flightMcp = McpClientBuilder
                .create("flight-mcp")
                .stdioTransport(
                        Path.of(javaHome, "bin", "java").toString(),
                        "-cp", classpath,
                        "com.ycy.travelagent.mcp.FlightMcpServer")
                .buildAsync()
                .block();
        toolkit.registerMcpClient(flightMcp).block();

        // DEFAULT 模式下未匹配规则会触发 ASK；只读工具与"创建审批单"直接放行，
        // 真正的人工确认由业务审批流承担（submit_travel_approval -> PENDING 单据）。
        PermissionContextState permissionContext = PermissionContextState.builder()
                .mode(PermissionMode.DEFAULT)
                .addAllowRule("plan_exit", new PermissionRule("plan_exit", null, PermissionBehavior.ALLOW, "projectSettings"))
                .addAllowRule("memory_search", new PermissionRule("memory_search", null, PermissionBehavior.ALLOW, "projectSettings"))
                .addAllowRule("memory_save", new PermissionRule("memory_save", null, PermissionBehavior.ALLOW, "projectSettings"))
                .addAllowRule("query_travel_policy", new PermissionRule("query_travel_policy", null, PermissionBehavior.ALLOW, "projectSettings"))
                .addAllowRule("search_flights", new PermissionRule("search_flights", null, PermissionBehavior.ALLOW, "projectSettings"))
                .addAllowRule("submit_travel_approval", new PermissionRule("submit_travel_approval", null, PermissionBehavior.ALLOW, "projectSettings"))
                .addAllowRule("book_travel", new PermissionRule("book_travel", null, PermissionBehavior.ALLOW, "projectSettings"))
                .addAllowRule("query_my_approvals", new PermissionRule("query_my_approvals", null, PermissionBehavior.ALLOW, "projectSettings"))
                .addAllowRule("query_my_orders", new PermissionRule("query_my_orders", null, PermissionBehavior.ALLOW, "projectSettings"))
                .build();

        return HarnessAgent.builder()
                .name("travel-orchestrator")
                .agentId("travel-orchestrator")
                .sysPrompt("""
                        你是企业差旅助手，负责差旅规划与执行。
                        规则：
                        1. 查询差标调用 query_travel_policy，只需要提供目的城市；员工职级由系统自动提供，不要询问也不要假设。
                        2. 需要提交审批时，必须调用 submit_travel_approval，并原样把审批单号告诉用户。
                        3. 审批单处于 PENDING 或 REJECTED 时，禁止调用 book_travel，也不要声称已经预订。
                        4. 用户明确告知审批已通过后，才可以调用 book_travel 下单；下单时如实填写金额、城市、舱位/晚数。
                        5. 下单失败时，把失败原因原样告诉用户，不要编造成功。
                        6. 回答使用中文，简洁、可执行。
                        """)
                .model(model)
                .toolkit(toolkit)
                .permissionContext(permissionContext)
                .workspace(workspace)
                .enablePlanMode(true)
                .stateStore(stateStore)
                .compaction(CompactionConfig.builder()
                        .triggerMessages(30)
                        .keepMessages(10)
                        .flushBeforeCompact(true)
                        .build());
    }
}
