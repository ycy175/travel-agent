package com.ycy.travelagent.config;

import java.nio.file.Files;
import java.nio.file.Path;

public class WorkspaceInitializer {
    public static Path init() throws Exception {
        Path workspace = Path.of(".agentscope/workspace");
        Files.createDirectories(workspace);
        Path agentsMd = workspace.resolve("AGENTS.md");
        if (!Files.exists(agentsMd)) {
            Files.writeString(agentsMd, """
                # 差旅助手
                你是一个企业差旅规划助手。
                ## 行为约定
                - 先收集出发地、目的地、时间、预算、差旅标准。
                - 涉及预订、修改、提交审批等写操作，必须进入人工确认。
                - 不确定的舱位/酒店政策，先查询工具，不要臆造。
                """);
        }
        return workspace;
    }
}