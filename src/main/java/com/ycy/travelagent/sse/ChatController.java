package com.ycy.travelagent.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ycy.travelagent.audit.AuditService;
import com.ycy.travelagent.auth.AuthInterceptor;
import com.ycy.travelagent.chat.ChatRepository;
import com.ycy.travelagent.common.ApiException;
import com.ycy.travelagent.usage.UsageService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 对话入口：
 * - 身份来自鉴权拦截器，不再信任 URL 上的 userId
 * - 用户消息与助手回复都落库，支持刷新/换设备恢复
 * - token 用量按配置计价，并随消息一起持久化
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private static final String EV_TEXT = "text";
    private static final String EV_REASONING = "reasoning";
    private static final String EV_TOOL = "tool";
    private static final String EV_NOTICE = "notice";
    private static final String EV_USAGE = "usage";
    private static final String EV_DONE = "done";
    private static final String EV_SERVER_ERROR = "server_error";

    private static final long HEARTBEAT_SECONDS = 15;
    private static final int MAX_MESSAGE_LENGTH = 4000;

    private final HarnessAgent agent;
    private final ChatRepository chatRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final UsageService usageService;
    private final AuditService auditService;
    private final String modelName;
    private final BigDecimal promptPrice;
    private final BigDecimal completionPrice;

    /** 缓存"没有 getUsage 方法"的事件类型，避免每个增量事件都做一次反射。 */
    private final Set<Class<?>> noUsageTypes = ConcurrentHashMap.newKeySet();

    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    public ChatController(HarnessAgent agent,
                          ChatRepository chatRepository,
                          ObjectMapper objectMapper,
                          MeterRegistry meterRegistry,
                          UsageService usageService,
                          AuditService auditService,
                          @Value("${agentscope.model.openai.model-name}") String modelName,
                          @Value("${app.billing.prompt-price-per-million:1.0}") BigDecimal promptPrice,
                          @Value("${app.billing.completion-price-per-million:2.0}") BigDecimal completionPrice) {
        this.agent = agent;
        this.chatRepository = chatRepository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.usageService = usageService;
        this.auditService = auditService;
        this.modelName = modelName;
        this.promptPrice = promptPrice;
        this.completionPrice = completionPrice;
    }

    @PreDestroy
    void stopScheduler() {
        heartbeatScheduler.shutdownNow();
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestParam String sessionId,
                                 @RequestParam String message,
                                 HttpServletRequest request) {
        String userId = (String) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        chatRepository.findOwned(sessionId, userId)
                .orElseThrow(() -> ApiException.notFound("会话不存在"));
        if (message == null || message.isBlank()) {
            throw ApiException.badRequest("消息不能为空");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw ApiException.badRequest("消息过长，请控制在 " + MAX_MESSAGE_LENGTH + " 字以内");
        }
        usageService.ensureWithinQuota(userId);

        chatRepository.appendMessage(sessionId, "user", message, null, null, null);
        chatRepository.titleFromFirstMessage(sessionId, titleOf(message));

        SseEmitter emitter = new SseEmitter(0L);
        RunState state = new RunState(emitter, sessionId, userId);

        RuntimeContext ctx = RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .build();

        AtomicBoolean closed = state.closed;
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (closed.get()) {
                return;
            }
            try {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event().comment("ping"));
                }
            } catch (Exception e) {
                log.debug("SSE heartbeat failed: {}", e.getMessage());
                completeQuietly(emitter);
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);

        Runnable cleanup = () -> {
            if (!state.closed.compareAndSet(false, true)) {
                return;
            }
            Disposable d = state.subscription;
            if (d != null && !d.isDisposed()) {
                d.dispose();
            }
            // 不关闭 agent：它是应用级单例，close 会销毁 MCP 客户端等共享资源。
            // 由 Spring 容器在应用退出时统一关闭（见 AgentConfig 的 @Bean destroyMethod）。
        };

        emitter.onCompletion(() -> {
            heartbeat.cancel(true);
            cleanup.run();
        });
        emitter.onTimeout(() -> {
            heartbeat.cancel(true);
            completeQuietly(emitter);
            cleanup.run();
        });
        emitter.onError(t -> {
            log.debug("SSE transport error: {}", t.getMessage());
            heartbeat.cancel(true);
            cleanup.run();
        });

        Disposable subscription = agent.streamEvents(new UserMessage(message), ctx)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        event -> dispatch(state, event),
                        error -> {
                            persist(state);
                            if (state.closed.get()) {
                                return;
                            }
                            Throwable cause = Exceptions.unwrap(error);
                            if (cause instanceof CancellationException) {
                                completeQuietly(emitter);
                                return;
                            }
                            log.error("Agent stream failed", cause);
                            send(state, EV_SERVER_ERROR,
                                    new SsePayload("error", "服务端执行失败：" + cause.getMessage(),
                                            null, null, "ERROR", null));
                            completeQuietly(emitter);
                        },
                        () -> {
                            persist(state);
                            if (!state.closed.get() && state.doneSent.compareAndSet(false, true)) {
                                send(state, EV_DONE, new SsePayload("done", null, null, null, "END", null));
                            }
                            completeQuietly(emitter);
                        });

        state.subscription = subscription;
        return emitter;
    }

    private void dispatch(RunState state, AgentEvent event) {
        if (state.closed.get()) {
            return;
        }

        int[] usage = readUsage(event);
        if (usage != null) {
            state.promptTokens.addAndGet(usage[0]);
            state.completionTokens.addAndGet(usage[1]);
            meterRegistry.counter("agent.tokens.prompt", "model", modelName).increment(usage[0]);
            meterRegistry.counter("agent.tokens.completion", "model", modelName).increment(usage[1]);
        }

        switch (event.getType()) {
            case TEXT_BLOCK_DELTA -> {
                String delta = ((TextBlockDeltaEvent) event).getDelta();
                if (delta != null && !delta.isEmpty()) {
                    state.answer.append(delta);
                    send(state, EV_TEXT, new SsePayload("text", delta, null, null, null, null));
                }
            }
            case THINKING_BLOCK_DELTA -> {
                String delta = ((ThinkingBlockDeltaEvent) event).getDelta();
                if (delta != null && !delta.isEmpty()) {
                    send(state, EV_REASONING, new SsePayload("reasoning", delta, null, null, null, null));
                }
            }
            case TOOL_CALL_START -> {
                ToolCallStartEvent e = (ToolCallStartEvent) event;
                send(state, EV_TOOL, new SsePayload("tool", null, e.getToolCallName(), e.getToolCallId(), "START", null));
            }
            case TOOL_RESULT_END -> {
                ToolResultEndEvent e = (ToolResultEndEvent) event;
                send(state, EV_TOOL, new SsePayload("tool", null, e.getToolCallName(), e.getToolCallId(),
                        "RESULT_END", e.getState() == null ? null : e.getState().name()));
            }
            case REQUIRE_USER_CONFIRM -> send(state, EV_NOTICE,
                    new SsePayload("notice", "该操作需要人工确认后才会继续执行。", null, null, "CONFIRM", null));
            case EXCEED_MAX_ITERS -> send(state, EV_NOTICE,
                    new SsePayload("notice", "已达到最大推理轮数（" + ((ExceedMaxItersEvent) event).getMaxIters()
                            + "），本轮提前结束。", null, null, "MAX_ITERS", null));
            case AGENT_END -> {
                if (state.doneSent.compareAndSet(false, true)) {
                    send(state, EV_USAGE, new SsePayload("usage", usageJson(state), null, null, "USAGE", null));
                    send(state, EV_DONE, new SsePayload("done", null, null, null, "END", null));
                }
            }
            default -> {
                // 其余事件不向前端暴露
            }
        }
    }

    private void persist(RunState state) {
        if (!state.persisted.compareAndSet(false, true)) {
            return;
        }
        String text = state.answer.toString();
        if (text.isBlank()) {
            return;
        }
        try {
            int prompt = state.promptTokens.get();
            int completion = state.completionTokens.get();
            BigDecimal cost = cost(prompt, completion);
            chatRepository.appendMessage(state.sessionId, "assistant", text,
                    prompt, completion, cost);
            auditService.record(state.userId, state.sessionId, AuditService.ACTION_CHAT, null,
                    "prompt=" + prompt + ", completion=" + completion + ", cost=" + cost,
                    true, null, null);
        } catch (Exception e) {
            log.warn("持久化助手消息失败: {}", e.getMessage());
        }
    }

    private BigDecimal cost(int promptTokens, int completionTokens) {
        BigDecimal promptCost = promptPrice.multiply(BigDecimal.valueOf(promptTokens))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        BigDecimal completionCost = completionPrice.multiply(BigDecimal.valueOf(completionTokens))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        return promptCost.add(completionCost);
    }

    private String usageJson(RunState state) {
        int prompt = state.promptTokens.get();
        int completion = state.completionTokens.get();
        BigDecimal cost = cost(prompt, completion);
        try {
            return objectMapper.writeValueAsString(new Usage(prompt, completion, cost.setScale(4, RoundingMode.HALF_UP)));
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 从事件中尽力提取 usage。不同事件类型携带位置不同，因此先看自身再退到 result；
     * 对确定没有 usage 的类型做缓存，避免重复反射。
     */
    private int[] readUsage(AgentEvent event) {
        Class<?> type = event.getClass();
        if (noUsageTypes.contains(type)) {
            return null;
        }
        try {
            Object usage;
            try {
                usage = type.getMethod("getUsage").invoke(event);
            } catch (NoSuchMethodException e) {
                usage = null;
            }
            if (usage == null) {
                Object result;
                try {
                    result = type.getMethod("getResult").invoke(event);
                } catch (NoSuchMethodException e) {
                    result = null;
                }
                if (result != null) {
                    try {
                        usage = result.getClass().getMethod("getUsage").invoke(result);
                    } catch (NoSuchMethodException ignored) {
                        usage = null;
                    }
                }
            }
            if (usage == null) {
                noUsageTypes.add(type);
                return null;
            }
            int input = ((Number) usage.getClass().getMethod("getInputTokens").invoke(usage)).intValue();
            int output = ((Number) usage.getClass().getMethod("getOutputTokens").invoke(usage)).intValue();
            return new int[]{Math.max(input, 0), Math.max(output, 0)};
        } catch (Exception e) {
            return null;
        }
    }

    private void send(RunState state, String eventName, SsePayload payload) {
        if (state.closed.get()) {
            return;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("Failed to serialize SSE payload", e);
            return;
        }
        try {
            synchronized (state.emitter) {
                state.emitter.send(SseEmitter.event().name(eventName).data(json, MediaType.TEXT_PLAIN));
            }
        } catch (Exception e) {
            log.debug("SSE send failed ({}): {}", eventName, e.getMessage());
            completeQuietly(state.emitter);
        }
    }

    private String titleOf(String message) {
        String oneLine = message.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 30 ? oneLine.substring(0, 30) + "..." : oneLine;
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 客户端已断开
        }
    }

    public record Usage(int prompt, int completion, BigDecimal cost) {
    }

    public record SsePayload(String type, String content, String toolName, String toolCallId,
                             String phase, String state) {
    }

    /** 单次对话的运行态，替代散落的 Atomic* 参数列表。 */
    private static final class RunState {
        private final SseEmitter emitter;
        private final String sessionId;
        private final String userId;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean doneSent = new AtomicBoolean(false);
        private final AtomicBoolean persisted = new AtomicBoolean(false);
        private final AtomicInteger promptTokens = new AtomicInteger(0);
        private final AtomicInteger completionTokens = new AtomicInteger(0);
        private final StringBuilder answer = new StringBuilder();
        private volatile Disposable subscription;

        private RunState(SseEmitter emitter, String sessionId, String userId) {
            this.emitter = emitter;
            this.sessionId = sessionId;
            this.userId = userId;
        }
    }
}
