package com.mewcode.cli;

import com.mewcode.core.App;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * MewCode 主入口 —— 一个终端 AI 编程助手。
 *
 * @author MewCode
 * @since 0.1.0
 */
public class Main {

    public static void main(String[] args) {
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
