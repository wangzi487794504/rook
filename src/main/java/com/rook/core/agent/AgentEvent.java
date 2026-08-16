package com.rook.core.agent;

import com.rook.tools.ToolCall;
import com.rook.tools.ToolResult;

/**
 * Agent 事件载体，通过事件管道在 AgentLoop 和界面之间传递。
 *
 * @author wangzijie
 * @since 0.3.0
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public record AgentEvent(
        AgentEventType type,
        String text,
        ToolCall toolCall,
        ToolResult toolResult,
        int iteration,
        int totalIterations) {

    public static AgentEvent text(String token) {
        return new AgentEvent(AgentEventType.TEXT, token, null, null, 0, 0);
    }

    public static AgentEvent toolCall(ToolCall tc) {
        return new AgentEvent(AgentEventType.TOOL_CALL, null, tc, null, 0, 0);
    }

    public static AgentEvent toolResult(ToolResult tr) {
        return new AgentEvent(AgentEventType.TOOL_RESULT, null, null, tr, 0, 0);
    }

    public static AgentEvent progress(int iteration, int totalIterations) {
        return new AgentEvent(AgentEventType.PROGRESS, null, null, null, iteration, totalIterations);
    }

    public static AgentEvent error(String msg) {
        return new AgentEvent(AgentEventType.ERROR, msg, null, null, 0, 0);
    }

    public static AgentEvent done() {
        return new AgentEvent(AgentEventType.DONE, null, null, null, 0, 0);
    }
}
