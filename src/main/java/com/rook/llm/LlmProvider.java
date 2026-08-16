package com.rook.llm;

import com.rook.tools.ToolRegistry;

import java.util.List;

/**
 * LLM Provider 抽象接口，定义统一的 API 调用方式。
 *
 * @author wangzijie
 * @since 0.1.0
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public interface LlmProvider {

    /**
     * 返回此 Provider 的名称（对应配置中的 name）。
     */
    String name();

    /**
     * 发送消息列表并流式返回回复。不携带工具定义，仅做纯对话。
     *
     * @param messages 消息历史（包括本次用户输入）
     * @param callback 流式回调
     * @throws LlmException 调用失败时抛出
     */
    default void stream(List<ChatMessage> messages, StreamCallback callback) throws LlmException {
        stream(messages, null, callback);
    }

    /**
     * 发送消息列表并流式返回回复，携带工具定义。
     *
     * @param messages 消息历史（包括本次用户输入）
     * @param tools    工具注册中心，null 表示不使用工具
     * @param callback 流式回调
     * @throws LlmException 调用失败时抛出
     */
    void stream(List<ChatMessage> messages, ToolRegistry tools, StreamCallback callback)
            throws LlmException;

    /**
     * 发送消息列表，同步等待完整回复后返回。不携带工具定义。
     *
     * @param messages 消息历史
     * @return 完整的回复内容
     * @throws LlmException 调用失败时抛出
     */
    default String complete(List<ChatMessage> messages) throws LlmException {
        StringBuilder sb = new StringBuilder();
        stream(messages, null, sb::append);
        return sb.toString();
    }

    /**
     * 返回最后一次 stream() 调用的 token 用量。
     *
     * @return 用量，从未调用过则返回 Usage.ZERO
     */
    Usage lastUsage();
}
