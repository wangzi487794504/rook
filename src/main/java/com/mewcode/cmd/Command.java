package com.mewcode.cmd;

import java.util.List;

/** 命令元数据 + 处理函数 */
public record Command(
        String name,
        List<String> aliases,
        String description,
        String usage,
        CommandType type,
        boolean hidden,
        CommandHandler handler) {

    @FunctionalInterface
    public interface CommandHandler {
        /** 执行命令。返回值 null 表示纯输出；非 null 的 PROMPT_FEED 文本会注入对话。 */
        String execute(Console console, String args);
    }
}
