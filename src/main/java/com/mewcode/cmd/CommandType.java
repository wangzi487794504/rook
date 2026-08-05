package com.mewcode.cmd;

/** 命令执行类型 */
public enum CommandType {
    LOCAL,        // 纯本地输出
    UI_AFFECTING, // 改变界面/运行模式
    PROMPT_FEED   // 注入提示词+触发LLM
}
