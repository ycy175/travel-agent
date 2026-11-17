package com.ycy.travelagent.config;

import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 锁定「Agent 应用级单例」这一并发契约。
 *
 * <p>背景：改造前每个 SSE 请求都 synchronized(builder) + build() 一个新 Agent，
 * 且请求结束即 close()。前者让所有用户对话全局串行，后者会销毁共享的 MCP 客户端。
 *
 * <p>改造后 Agent 在启动时构建一次、全局复用，框架按 (userId, sessionId) 隔离状态。
 * 本测试确保没人把 bean 改回 prototype 或注入 Builder 后重复 build。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agentsingleton;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "app.security.token-secret=test-secret",
        "agentscope.model.openai.api-key=test-key"
})
class AgentSingletonTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private HarnessAgent agent;

    @Test
    void agentIsApplicationScopedSingleton() {
        HarnessAgent fromContext = context.getBean(HarnessAgent.class);
        assertNotNull(agent);
        assertSame(agent, fromContext, "Agent 必须是单例，多次获取应返回同一实例");
        assertSame(agent, context.getBean(HarnessAgent.class),
                "重复 getBean 必须返回同一实例（防止被改成 prototype）");
    }

    @Test
    void onlyOneAgentBeanDefined() {
        assertNotNull(context.getBean(HarnessAgent.class));
        org.junit.jupiter.api.Assertions.assertEquals(1,
                context.getBeansOfType(HarnessAgent.class).size(),
                "容器中只应有一个 Agent bean");
    }
}
