package com.rook.cli;

import com.rook.core.App;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
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
        // 统一 UTF-8 输出，避免 Windows 默认 GBK 导致中文乱码
        try {
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // 输出流重定向失败时保持默认，不阻塞启动
        }

        String configPath = "./config.yaml";

        // 解析命令行参数
        for (int i = 0; i < args.length; i++) {
            if ("--config".equals(args[i]) && i + 1 < args.length) {
                configPath = args[++i];
            }
        }

        try {
            new App().run(Paths.get(configPath));
        } catch (Exception e) {
            System.err.println("启动失败: " + e.getMessage());
            System.exit(1);
        }
    }
}
