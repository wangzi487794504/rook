package com.rook.context;

import com.rook.llm.*;

import java.util.*;

/**
 * 上下文管理器 —— AgentLoop 的唯一入口。
 * prepare() 先跑第1层（工具结果存盘），再跑第2层（摘要）。
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class ContextManager {

    private final ContextConfig config;
    private final ToolResultStorage storage;
    private final ContextSummarizer summarizer;
    private final TokenEstimator estimator;
    private Usage lastUsage = Usage.ZERO;
    private int totalSavedBytes = 0;

    /** @return 方法返回值 */
    public ContextManager(ContextConfig config, java.nio.file.Path contextDir, String toolsJson) {
        this.config = config;
        this.storage = new ToolResultStorage(contextDir, config.maxToolResultBytes());
        this.summarizer = new ContextSummarizer();
        this.estimator = new TokenEstimator(toolsJson);
    }

    /**
     * AgentLoop 每轮调用前执行。
     * @return 处理后的消息列表（可能已压缩）
     */
    /** @return 方法返回值 */
    public List<ChatMessage> prepare(List<ChatMessage> messages, LlmProvider provider, boolean isAuto) {
        if (!config.enabled()) return messages;

        // ★ 第1层：工具结果存盘替换
        messages = applyToolResultStorage(messages);

        // ★ 估算 token
        int margin = isAuto ? config.autoMargin() : config.manualMargin();
        int estimated = estimator.estimate(messages, lastUsage);
        int limit = config.contextWindow() - margin;

        if (estimated <= limit) return messages;

        // ★ 第2层：摘要
        if (summarizer.isTripped()) return messages; // 熔断

        messages = summarizer.summarize(messages, provider, estimator);
        return messages;
    }

    /** 第1层：遍历消息，将超大工具结果存盘替换 */
    private List<ChatMessage> applyToolResultStorage(List<ChatMessage> messages) {
        List<ChatMessage> out = new ArrayList<>(messages);
        for (int i = 0; i < out.size(); i++) {
            ChatMessage m = out.get(i);
            if (m.toolUseId() != null && m.content() != null) {
                String stored = storage.maybeStore(null, new com.rook.tools.ToolResult(true, m.content()));
                if (!stored.equals(m.content())) {
                    totalSavedBytes += m.content().length();
                    out.set(i, new ChatMessage(m.role(), stored, m.toolUseId(), m.toolName(), null));
                }
            }
        }
        return out;
    }

    /** 手动压缩 */
    public void compress(LlmProvider provider) {
        summarizer.reset();
    }

    /** 紧急压缩（prompt_too_long 触发） */
    /** @return 方法返回值 */
    public List<ChatMessage> emergencyCompress(List<ChatMessage> messages, LlmProvider provider) {
        return summarizer.summarize(messages, provider, estimator);
    }

    /** 更新锚定值 */
    public void updateUsage(Usage usage) {
        if (usage != null) this.lastUsage = usage;
    }

    /** @return 方法返回值 */
    public ContextStatus status() {
        int estimated = estimator.estimate(List.of(), lastUsage);
        return new ContextStatus(estimated, config.contextWindow(), totalSavedBytes, summarizer.isTripped());
    }

    public record ContextStatus(int estimatedTokens, int windowSize, int savedBytes, boolean tripped) {}
}
