package com.rook.cmd;

import java.io.IOException;
import java.util.List;

/** 注册全部 12 条内置命令 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class BuiltinCommands {

    /** 跨平台清屏：Windows 调 cmd /c cls，其他系统用 ANSI 码 */
    private static void clearScreen() {
        try {
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                System.out.print("\033[H\033[2J");
                System.out.flush();
            }
        } catch (IOException | InterruptedException ignored) {
            System.out.println("\n".repeat(50));
        }
    }

    public static void registerAll(CommandRegistry registry) {
        registry.register(new Command("help", List.of("h", "?"),
                "显示所有可用命令", "/help", CommandType.LOCAL, false,
                (c, args) -> {
                    StringBuilder sb = new StringBuilder();
                    for (Command cmd : registry.all()) {
                        if (cmd.hidden()) continue;
                        sb.append(String.format("  /%-15s %s%n", cmd.name(), cmd.description()));
                    }
                    c.display(sb.toString());
                    return null;
                }));

        registry.register(new Command("exit", List.of("q"),
                "退出 Rook", "/exit", CommandType.UI_AFFECTING, false,
                (c, args) -> { c.exit(); return null; }));

        registry.register(new Command("plan", List.of("p"),
                "进入计划模式（只读工具）", "/plan", CommandType.UI_AFFECTING, false,
                (c, args) -> { c.setPlanMode(true); c.display("已进入计划模式"); return null; }));

        registry.register(new Command("do", List.of("d"),
                "退出计划模式并执行", "/do", CommandType.PROMPT_FEED, false,
                (c, args) -> { c.setPlanMode(false); return "请按照之前制定的计划开始执行。"; }));

        registry.register(new Command("compact", List.of("comp"),
                "手动触发上下文压缩", "/compact", CommandType.UI_AFFECTING, false,
                (c, args) -> { c.compact(); c.display("已触发上下文压缩"); return null; }));

        registry.register(new Command("clear", List.of("cls"),
                "清空对话历史，开启新会话", "/clear", CommandType.UI_AFFECTING, false,
                (c, args) -> {
                    c.clearSession();
                    clearScreen();
                    c.display("会话已清空，开启新会话\n");
                    return null;
                }));

        registry.register(new Command("session", List.of("s"),
                "显示当前会话信息", "/session", CommandType.LOCAL, false,
                (c, args) -> { c.display("会话: " + c.sessionPath()); return null; }));

        registry.register(new Command("memory", List.of("mem"),
                "列出已加载的记忆文件", "/memory", CommandType.LOCAL, false,
                (c, args) -> { c.display("记忆: " + c.memoryFiles()); return null; }));

        registry.register(new Command("permission", List.of("perm"),
                "显示当前权限模式", "/permission", CommandType.LOCAL, false,
                (c, args) -> { c.display("权限模式: " + c.permissionMode()); return null; }));

        registry.register(new Command("status", List.of("stat"),
                "显示系统状态", "/status", CommandType.LOCAL, false,
                (c, args) -> {
                    String s = String.format("""
                            权限: %s | 模型: %s
                            Token: %s | 工具: %d | 记忆: %d
                            目录: %s""",
                            c.permissionMode(), c.modelName(),
                            c.tokenUsage(), c.toolCount(), c.memoryCount(), c.workingDir());
                    c.display(s);
                    return null;
                }));

        // /worktree — G13 TUI 命令
        registry.register(new Command("worktree", List.of("wt"),
                "Git Worktree 管理(create <名>|list|remove <名>)",
                "/worktree", CommandType.LOCAL, false,
                (c, args) -> {
                    String[] parts = args.split("\\s+", 3);
                    String sub = parts.length > 0 ? parts[0] : "list";
                    String slug = parts.length > 1 ? parts[1] : "";
                    if ("create".equals(sub)) {
                        c.display(c.worktreeCreate(slug));
                    } else if ("remove".equals(sub)) {
                        boolean discard = args.contains("--discard");
                        c.display(c.worktreeRemove(slug, discard));
                    } else {
                        c.display(c.worktreeList());
                    }
                    return null;
                }));

        // /team — G17 TUI 命令
        registry.register(new Command("team", List.of("tm"),
                "团队管理(list|create|delete)", "/team", CommandType.LOCAL, false,
                (c, args) -> { c.display("用法: /team create <名称> | list | delete <名称>"); return null; }));

        // /review 由内置 review Skill 提供，不在此重复注册
    }
}
