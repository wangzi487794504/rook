package com.rook.core.agent;

/**
 * Agent 事件类型。
 *
 * @author wangzijie
 * @since 0.3.0
 */
public enum AgentEventType {
    TEXT,
    TOOL_CALL,
    TOOL_RESULT,
    TOKEN_USAGE,
    PROGRESS,
    ERROR,
    DONE
}
