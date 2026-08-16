package com.rook.cli;

import com.rook.core.App;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Rook 主入口 —— 一个终端 AI 编程助手。
 *
 * @author wangzijie
 * @since 0.1.0
 */
public class Main {

    public static void main(String[] args) {
        // 创建 JLine 终端，并把标准输出/错误重定向到终端的输出通道。
        // JLine 在 Windows 上直写控制台（Unicode），能绕开 GBK/UTF-8 字节编码
        // 不一致导致的中文乱码；终端同时传给 App/Repl 复用，避免重复创建。
        Terminal terminal = null;
        try {
            terminal = TerminalBuilder.builder()
                    .encoding(StandardCharsets.UTF_8)
                    .build();
            PrintStream out = new PrintStream(terminal.output(), true, terminal.encoding());
            System.setOut(out);
            System.setErr(out);
        } catch (Exception ignored) {
            // 终端创建失败（如无控制台环境）时保持默认输出，不阻塞启动
        }

        String configPath = "./config.yaml";

        // 解析命令行参数
        for (int i = 0; i < args.length; i++) {
            if ("--config".equals(args[i]) && i + 1 < args.length) {
                configPath = args[++i];
            }
        }

        try {
            new App().run(Paths.get(configPath), terminal);
        } catch (Exception e) {
            System.err.println("启动失败: " + e.getMessage());
            System.exit(1);
        }
    }
}
