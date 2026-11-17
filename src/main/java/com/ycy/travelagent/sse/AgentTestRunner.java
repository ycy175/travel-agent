package com.ycy.travelagent.sse;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.Resource;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

// @Component
public class AgentTestRunner implements CommandLineRunner {

    @Resource
    private HarnessAgent.Builder builder;

    @Override
    public void run(String... args) {
        try (HarnessAgent agent = builder.build()) {
            // 固定的 sessionId，用于断电重启测试
            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("alice")
                    .sessionId("resume-test-03")
                    .build();

            // 这里我们通过环境变量控制执行哪一步，方便测试
            String step = System.getenv("TEST_STEP");

            if ("2".equals(step)) {
                System.out.println(">>> [重启后] 发送确认与继续指令...");
                // 给一个明确的、排除所有疑虑的指令
                Msg response = agent.call(new UserMessage(
                        "确认：我就是要 2026-11-11 当天去，住 2 晚。请按之前查到的方案，直接帮我提交 3500 元的审批单。"
                ), ctx).block();
            // if ("2".equals(step)) {
            //     // 第二段：重启后的继续指令
            //     System.out.println(">>> [重启后] 发送继续指令...");
            //     Msg response = agent.call(new UserMessage("任务还没做完，请继续执行你的计划。"), ctx).block();
            //     System.out.println("\n[重启后最终输出] \n" + response.getTextContent());
            } else {
                // 第一段：初始复杂任务
                String complexTask = "我是 M3 级别的员工，下周三（2026-11-11）要从北京出发去上海。"
                        + "请帮我做一份完整的差旅计划："
                        + "1. 先查一下我的差旅标准；"
                        + "2. 帮我找一个上午 10 点前起飞的航班；"
                        + "3. 最后帮我提交一个预计费用为 3500 元的审批单。";
                System.out.println(">>> [首次执行] 用户下达复杂任务: \n" + complexTask);
                Msg response = agent.call(new UserMessage(complexTask), ctx).block();
                System.out.println("\n[首次最终输出] \n" + response.getTextContent());
            }
        }
    }
}