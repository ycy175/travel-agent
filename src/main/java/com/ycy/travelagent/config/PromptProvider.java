package com.ycy.travelagent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 版本化系统提示词加载器。
 *
 * <p>Prompt 从 Java 代码外置到 classpath:prompts/travel-orchestrator-{version}.txt，
 * 调整话术无需改代码、无需重新编译；切换版本只需配置项 agentscope.prompt.version。
 * 每个版本的 Prompt 都是可 diff、可评审、可回归测试的资产。
 */
@Component
public class PromptProvider {

    private static final String PREFIX = "prompts/travel-orchestrator-";
    private static final String SUFFIX = ".txt";

    private final String prompt;

    public PromptProvider(@Value("${agentscope.prompt.version:v1}") String version) {
        this.prompt = load(version);
    }

    /** 当前生效的提示词（去掉首尾空白，避免文件末尾换行带来差异）。 */
    public String systemPrompt() {
        return prompt;
    }

    private String load(String version) {
        if (version == null || version.isBlank() || !version.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("非法 Prompt 版本号: " + version);
        }
        String path = PREFIX + version + SUFFIX;
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new IllegalStateException("无法加载系统提示词: " + path, e);
        }
    }
}
