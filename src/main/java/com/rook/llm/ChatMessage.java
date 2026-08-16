package com.rook.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rook.tools.ToolCall;

import java.util.List;

/**
 * 一条对话消息，支持三种类型：
 * <ul>
 *   <li>普通文本：{@code new ChatMessage("user", "你好")}</li>
 *   <li>工具结果回灌：{@code ChatMessage.toolResult(id, name, content)}</li>
 *   <li>Assistant含工具调用：{@code ChatMessage.assistantWithTools(text, calls)}</li>
 * </ul>
 *
 * @param role      角色：user / assistant / system
 * @param content   消息文本。tool_result 时为结果内容，assistant+tool 时为 thinking 文本
 * @param toolUseId 非 null 表示这是一条 tool_result 消息，值为对应的 tool_use id
 * @param toolName  tool_result 对应的工具名
 * @param toolCalls 非 null 表示这是一条 assistant 消息，携带工具调用列表
 * @author wangzijie
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

    /** 向后兼容的简单文本消息构造函数 */
    public ChatMessage(String role, String content) {
        this(role, content, null, null, null);
    }

    /**
     * 构造一条工具结果回灌消息。
     * Provider 会将此消息转换为 API 要求的 content block 格式。
     */
    public static ChatMessage toolResult(String toolUseId, String toolName, String content) {
        return new ChatMessage("user", content, toolUseId, toolName, null);
    }

    /**
     * 构造一条 assistant 消息，携带工具调用列表。
     * AgentLoop 在执行工具前将这个消息写入历史，Provider 将其展开为 content blocks。
     */
    public static ChatMessage assistantWithTools(String text, List<ToolCall> calls) {
        return new ChatMessage("assistant", text, null, null, calls);
    }
}
