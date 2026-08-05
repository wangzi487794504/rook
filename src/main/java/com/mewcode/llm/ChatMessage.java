package com.mewcode.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mewcode.tools.ToolCall;

import java.util.List;

/**
 * 一条对话消息。支持三种类型：
 * - 普通文本消息：role + content
 * - tool_result 消息：role=user, toolUseId/toolName + content
 * - assistant+tool_use 消息：role=assistant, content=文本, toolCalls=工具调用列表
 *
 * @author MewCode
 * @since 0.1.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(
        @JsonProperty("role") String role,
        @JsonProperty("content") String content,
        @JsonProperty("toolUseId") String toolUseId,
        @JsonProperty("toolName") String toolName,
        @JsonProperty("toolCalls") List<ToolCall> toolCalls) {

    /** 普通文本消息 */
    public ChatMessage(String role, String content) {
        this(role, content, null, null, null);
    }

    /** tool_result 消息 */
    public static ChatMessage toolResult(String toolUseId, String toolName, String content) {
        return new ChatMessage("user", content, toolUseId, toolName, null);
    }

    /** assistant 消息带工具调用（用于重建历史） */
    public static ChatMessage assistantWithTools(String text, List<ToolCall> calls) {
        return new ChatMessage("assistant", text, null, null, calls);
    }
}
