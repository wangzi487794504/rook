package com.rook.core.agent;

import com.rook.context.ContextManager;
import com.rook.hook.HookContext;
import com.rook.hook.HookEngine;
import com.rook.hook.HookEvent;
import com.rook.hook.HookPayload;
import com.rook.memory.AutoMemory;
import com.rook.llm.*;
import com.rook.prompt.PromptBuilder;
import com.rook.prompt.PromptContext;
import com.rook.prompt.SupplementMessage;
import com.rook.tools.*;
import org.jline.reader.LineReader;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * ReAct 循环引擎 —— 驱动多轮 LLM ↔ Tool 交互，通过事件管道与界面解耦。
 *
 * @author wangzijie
 * @since 0.3.0
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class AgentLoop {

    private final LoopConfig config;
    private final PromptBuilder promptBuilder;
    private final com.rook.permission.PermissionEngine permissionEngine;
    private final ContextManager contextManager;
    private final AutoMemory autoMemory;
    private final HookEngine hookEngine;
    private volatile LoopState state = LoopState.RUNNING;
    private int consecutiveErrors = 0;
    private boolean planMode = false;
    private boolean doModeTriggered = false;

    /** @return 方法返回值 */
    public AgentLoop(LoopConfig config, PromptBuilder promptBuilder,
                     com.rook.permission.PermissionEngine permissionEngine,
                     ContextManager contextManager,
                     AutoMemory autoMemory,
                     HookEngine hookEngine) {
        this.config = config;
        this.promptBuilder = promptBuilder;
        this.permissionEngine = permissionEngine;
        this.contextManager = contextManager;
        this.autoMemory = autoMemory;
        this.hookEngine = hookEngine;
    }

    public void setPlanMode(boolean planMode) {
        this.planMode = planMode;
        if (planMode) {
            this.doModeTriggered = false;
        }
    }

    public void triggerDoMode() {
        this.doModeTriggered = true;
        this.planMode = false;
    }

    /**
     * 运行 Agent Loop，通过事件管道返回进度。调用者从返回的队列中消费事件。
     *
     * @param history  消息历史（会被就地修改，追加每轮工具结果和 assistant 回复）
     * @param provider LLM 提供者
     * @param tools    可用工具注册表
     * @return 事件队列，DONE/ERROR 事件后不再有新事件
     */
    /** @return 方法返回值 */
    public BlockingQueue<AgentEvent> run(List<ChatMessage> history, LlmProvider provider,
                                         ToolRegistry tools, LineReader lineReader) {
        BlockingQueue<AgentEvent> eventQueue = new LinkedBlockingQueue<>(1000);
        state = LoopState.RUNNING;
        consecutiveErrors = 0;

        // 在后台线程执行循环
        Thread loopThread = new Thread(() -> {
            try {
                int iteration = 1;
                Usage totalUsage = Usage.ZERO;

                // ★ 注入 System Prompt（只注入一次，在消息列表最前）
                PromptContext pctx = new PromptContext(planMode, iteration,
                        System.getProperty("user.dir"), System.getProperty("os.name"), List.of());
                String systemPrompt = promptBuilder.build(pctx);
                if (!systemPrompt.isBlank()
                        && (history.isEmpty() || !"system".equals(history.get(0).role()))) {
                    history.add(0, new ChatMessage("system", systemPrompt));
                }

                // ★ 补充消息：循环开始前注入一次，后续只在间隔轮次注入本轮消息
                // 用于 Plan Mode 等长期开关的提醒，注入后不影响 history 的配对结构
                if (planMode) {
                    history.add(SupplementMessage.create(
                            "计划模式：只能使用只读工具（read_file、glob、grep）。分析代码并制定执行计划。"));
                }
                if (doModeTriggered) {
                    history.add(SupplementMessage.create(
                            "已切换到执行模式。请按照之前制定的计划开始执行，所有工具现在可用。"));
                    doModeTriggered = false;
                }

                while (state == LoopState.RUNNING) {
                    if (iteration > config.maxIterations()) {
                        state = LoopState.MAX_ITERATIONS;
                        eventQueue.put(AgentEvent.error("已达最大迭代次数 (" + config.maxIterations() + ")"));
                        break;
                    }

                    if (state == LoopState.CANCELLED) break;

                    eventQueue.put(AgentEvent.progress(iteration, config.maxIterations()));

                    // ★ Hook: 轮次开始
                    if (hookEngine != null) {
                        hookEngine.fire(HookEvent.PRE_USER_MESSAGE,
                                HookPayload.forSession(HookEvent.PRE_USER_MESSAGE, "round-" + iteration));
                    }

                    // ★ 间隔轮次重新注入补充消息（频率控制）
                    if (planMode && iteration > 1 && iteration % 3 == 0) {
                        history.add(SupplementMessage.create(
                                "仍在计划模式（当前第" + iteration + "轮）。继续只读操作。"));
                    }

                    // ★ 上下文管理：副本处理，不修改原始 history
                    List<ChatMessage> currentMessages = new java.util.ArrayList<>(history);
                    if (contextManager != null) {
                        currentMessages = contextManager.prepare(currentMessages, provider, true);
                    }

                    // 收集器
                    List<ChatMessage> msgs = currentMessages;
                    StreamCollector collector = new StreamCollector(
                            token -> {
                                try { eventQueue.put(AgentEvent.text(token)); }
                                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                            });

                    // 调用 LLM（含 prompt_too_long 紧急重试）
                    boolean streamOk = false;
                    try {
                        provider.stream(msgs, tools, collector);
                        streamOk = true;
                    } catch (LlmException e) {
                        if (e.getMessage() != null && e.getMessage().contains("prompt_too_long")
                                && contextManager != null) {
                            msgs = contextManager.emergencyCompress(
                                    new java.util.ArrayList<>(msgs), provider);
                            try {
                                provider.stream(msgs, tools, collector);
                                streamOk = true;
                            } catch (LlmException e2) { /* 重试失败 */ }
                        }
                        if (!streamOk) {
                            state = LoopState.ERROR;
                            eventQueue.put(AgentEvent.error("LLM 调用失败: " + e.getMessage()));
                            break;
                        }
                    }

                    // ★ 更新用量锚点
                    if (contextManager != null) {
                        contextManager.updateUsage(provider.lastUsage());
                    }

                    // 累积 token 用量
                    Usage roundUsage = collector.getUsage();
                    totalUsage = totalUsage.add(roundUsage);

                    // 获取结果
                    List<ToolCall> toolCalls = collector.getToolCalls();
                    String text = collector.getFullText();

                    // 没有工具调用 → 模型完成
                    if (toolCalls.isEmpty()) {
                        state = LoopState.COMPLETED;
                        if (!text.isBlank()) {
                            history.add(new ChatMessage("assistant", text));
                        }
                        break;
                    }

                    // 检查未知工具
                    for (ToolCall tc : toolCalls) {
                        if (tools.get(tc.name()).isEmpty()) {
                            state = LoopState.ERROR;
                            eventQueue.put(AgentEvent.error("调用了未知工具: " + tc.name()));
                            break;
                        }
                    }
                    if (state == LoopState.ERROR) break;

                    // 通知工具调用
                    for (ToolCall tc : toolCalls) {
                        eventQueue.put(AgentEvent.toolCall(tc));
                    }

                    // ★ 保存 assistant 消息（含 tool_use blocks），确保 API 上下文正确
                    history.add(ChatMessage.assistantWithTools(text, toolCalls));

                    // 执行工具
                    ToolExecutor executor = new ToolExecutor(tools, config.maxParallelReads(),
                            permissionEngine, hookEngine);
                    if (lineReader != null) executor.setLineReader(lineReader);
                    List<ToolResult> results = executor.execute(toolCalls);

                    // 回灌结果到历史（不添加包装 assistant 消息 —— 模型会在下轮自己生成）
                    for (int i = 0; i < toolCalls.size(); i++) {
                        ToolCall tc = toolCalls.get(i);
                        ToolResult tr = results.get(i);
                        eventQueue.put(AgentEvent.toolResult(tr));
                        history.add(ChatMessage.toolResult(tc.id(), tc.name(),
                                tr.success() ? tr.content() : "ERROR: " + tr.content()));
                    }

                    // 错误计数（PERMISSION_DENIED 不算连续错误，让模型调整）
                    boolean allSuccess = results.stream().allMatch(ToolResult::success);
                    boolean anyPermissionDenied = results.stream().anyMatch(
                            r -> !r.success() && r.content() != null
                                    && r.content().startsWith("PERMISSION_DENIED:"));
                    if (allSuccess || anyPermissionDenied) {
                        consecutiveErrors = 0; // 鉴权拒绝不累计错误
                    } else {
                        consecutiveErrors++;
                        if (consecutiveErrors >= config.consecutiveErrorLimit()) {
                            state = LoopState.ERROR;
                            eventQueue.put(AgentEvent.error(
                                    "连续 " + config.consecutiveErrorLimit() + " 次工具执行失败，已停止"));
                            break;
                        }
                    }

                    iteration++;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                state = LoopState.CANCELLED;
            } catch (Exception e) {
                try {
                    eventQueue.put(AgentEvent.error("Agent Loop 异常: " + e.getMessage()));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                state = LoopState.ERROR;
            } finally {
                // ★ 自然完成时异步更新记忆
                if (autoMemory != null && state == LoopState.COMPLETED) {
                    autoMemory.updateAsync(history, provider);
                }
                try {
                    eventQueue.put(AgentEvent.done());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "agent-loop");

        loopThread.start();
        return eventQueue;
    }

    /** 取消当前循环。 */
    public void cancel() {
        state = LoopState.CANCELLED;
    }

    /** 当前状态。 */
    /** @return 方法返回值 */
    public LoopState getState() {
        return state;
    }
}
