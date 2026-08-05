package com.mewcode.cmd;

/**
 * 命令分发器 —— 解析 /xxx 输入，匹配命令并执行。
 */
public class CommandDispatcher {

    private final CommandRegistry registry;

    public CommandDispatcher(CommandRegistry registry) {
        this.registry = registry;
    }

    /**
     * 解析并执行。命令名大小写不敏感，参数字面透传。
     * @return PROMPT_FEED 类型时返回注入文本（由调用方送 Agent）
     */
    public String dispatch(String input, Console console) {
        if (input == null || input.isBlank()) return null;

        // 去掉 / 前缀
        String rest = input.substring(1).trim();
        if (rest.isEmpty()) {
            console.display("输入 /help 查看可用命令");
            return null;
        }

        // 拆分命令名和参数
        String cmdName;
        String args;
        int spaceIdx = rest.indexOf(' ');
        if (spaceIdx > 0) {
            cmdName = rest.substring(0, spaceIdx);
            args = rest.substring(spaceIdx + 1);
        } else {
            cmdName = rest;
            args = "";
        }

        // 本期零参数：有非空白参数按未命中处理
        if (!args.isBlank()) {
            console.display("未知命令。输入 /help 查看帮助");
            return null;
        }

        cmdName = cmdName.toLowerCase();
        var cmd = registry.get(cmdName);
        if (cmd.isEmpty()) {
            console.display("未知命令。输入 /help 查看帮助");
            return null;
        }

        return cmd.get().handler().execute(console, args);
    }

    public CommandRegistry getRegistry() { return registry; }
}
