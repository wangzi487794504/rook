package com.mewcode.prompt;

import com.mewcode.llm.ChatMessage;

/**
 * 补充消息构造器 —— 以 [Supplement] 标签包装运行时指令，
 * 不污染缓存，不被模型当作用户输入来回复。
 *
 * @author MewCode
 * @since 0.4.0
 */
public class SupplementMessage {

    private static final String PREFIX = "[Supplement] ";

    /**
     * 创建一条带 [Supplement] 标签的补充消息。
     * role 为 "user" 以绕过缓存，前缀标记区分普通用户输入。
     */
    public static ChatMessage create(String instruction) {
        return new ChatMessage("user", PREFIX + instruction);
    }

    /** 判断一条消息是否为补充消息 */
    public static boolean isSupplement(ChatMessage msg) {
        return msg.content() != null && msg.content().startsWith(PREFIX);
    }
}
