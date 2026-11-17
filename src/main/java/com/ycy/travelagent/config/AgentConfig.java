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
    private final PromptProvider promptProvider;

    public AgentConfig(TravelTools travelTools, DataSource dataSource, PromptProvider promptProvider) {
        this.travelTools = travelTools;
        this.dataSource = dataSource;
        this.promptProvider = promptProvider;
    }

    /**
     * 单例 Agent：应用启动时构建一次，所有会话复用。
     *
     * <p>为什么不每次请求 build：AgentScope 的 Builder.build() 会修改 builder 自身字段
     * （如 stateStoreOverride），并发 build 同一 builder 不安全；而反复 build 还会不断
     * copy toolkit、重建 workspace 索引、重连 MCP 子进程，代价极高。
     *
     * <p>为什么单例安全：ReActAgent 内部用 ConcurrentHashMap 按 (userId, sessionId)
     * 隔离状态与权限引擎，callSerializationKey 也是 slotKey(userId, sessionId)，
     * 即不同会话天然并发、同一会话自动串行——框架本身就是按"单实例多会话"设计的。
     * 因此这里不逐请求 close（close 会关掉 MCP 客户端等共享资源），交给容器在
     * 应用退出时通过 destroyMethod 统一关闭。
     */
    @Bean(destroyMethod = "close")
    public HarnessAgent harnessAgent() throws Exception {
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
                .sysPrompt(promptProvider.systemPrompt())
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
                        .build())
                .build();
    }
}
