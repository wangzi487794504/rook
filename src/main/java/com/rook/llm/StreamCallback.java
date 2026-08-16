package com.rook.llm;

import com.rook.tools.ToolCall;

/**
 * 流式回调接口，每收到一个文本 token 时调用。
 *
 * @author wangzijie
 * @since 0.1.0
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public interface StreamCallback {

    void onToken(String token);

    default void onToolCall(ToolCall toolCall) {
        // 默认空实现
    }

    /** 每轮流式结束时回调 token 用量，默认空实现。 */
    default void onUsage(Usage usage) {
        // 默认空实现
    }
}
