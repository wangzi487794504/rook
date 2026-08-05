package com.mewcode.core.agent;

/**
 * Agent 事件类型。
 *
 * @author MewCode
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
