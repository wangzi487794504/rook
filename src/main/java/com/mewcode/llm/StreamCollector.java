package com.mewcode.llm;

import com.mewcode.tools.ToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 流式双路收集器 —— 实现 StreamCallback，同时走实时推送和累积拼接两路。
 *
 * @author MewCode
 * @since 0.3.0
 */
public class StreamCollector implements StreamCallback {

    private final Consumer<String> realtimeSink;
    private final StringBuilder fullText = new StringBuilder();
    private final List<ToolCall> toolCalls = new ArrayList<>();
    private Usage usage = Usage.ZERO;

    /**
     * @param realtimeSink 实时推送通道，每收到 token 立刻调用
     */
    public StreamCollector(Consumer<String> realtimeSink) {
        this.realtimeSink = realtimeSink;
    }

    @Override
    public void onToken(String token) {
        // 实时推送
        if (realtimeSink != null) {
            realtimeSink.accept(token);
        }
        // 累积拼接
        fullText.append(token);
    }

    @Override
    public void onToolCall(ToolCall toolCall) {
        toolCalls.add(toolCall);
    }

    @Override
    public void onUsage(Usage u) {
        this.usage = u;
    }

    /** 累积的完整文本 */
    public String getFullText() {
        return fullText.toString();
    }

    /** 本轮累积的所有工具调用 */
    public List<ToolCall> getToolCalls() {
        return List.copyOf(toolCalls);
    }

    /** 本轮 token 用量 */
    public Usage getUsage() {
        return usage;
    }
}
