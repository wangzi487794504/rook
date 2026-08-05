package com.mewcode.tools.impl;

import com.mewcode.tools.Tool;
import com.mewcode.tools.ToolResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 执行命令工具 —— 在 shell 中执行命令，带超时机制。
 *
 * @author MewCode
 * @since 0.2.0
 */
public class ExecCommandTool implements Tool {

    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_BYTES = 100 * 1024; // 100KB

    @Override
    public String name() {
        return "exec_command";
    }

    @Override
    public String description() {
        return "在 shell 中执行一条命令。命令在非交互式 shell 中运行，超时30秒。返回 stdout、stderr 和退出码。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "command", Map.of("type", "string", "description", "要执行的 shell 命令"),
                "workingDir", Map.of("type", "string",
                        "description", "工作目录，可选，默认为当前目录")
        ));
        schema.put("required", List.of("command"));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String command = (String) params.get("command");
        if (command == null || command.isBlank()) {
            return ToolResult.error("缺少参数: command");
        }

        String workingDir = (String) params.getOrDefault("workingDir", ".");
        Path workPath = Path.of(workingDir);

        try {
            // Windows 用 cmd /c，其他用 sh -c
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            ProcessBuilder pb;
            if (isWindows) {
                pb = new ProcessBuilder("cmd", "/c", command);
            } else {
                pb = new ProcessBuilder("sh", "-c", command);
            }
            pb.directory(workPath.toFile());
            pb.redirectErrorStream(true); // 合并 stderr 到 stdout

            Process process = pb.start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            // 读取输出（限制大小）
            try (var is = process.getInputStream()) {
                byte[] buf = new byte[8192];
                int totalRead = 0;
                int read;
                while ((read = is.read(buf)) != -1) {
                    if (totalRead + read > MAX_OUTPUT_BYTES) {
                        int remaining = MAX_OUTPUT_BYTES - totalRead;
                        if (remaining > 0) {
                            output.write(buf, 0, remaining);
                        }
                        // 丢弃剩余
                        break;
                    }
                    output.write(buf, 0, read);
                    totalRead += read;
                }
                if (totalRead >= MAX_OUTPUT_BYTES) {
                    output.write("\n...(输出已截断，超过100KB)".getBytes(StandardCharsets.UTF_8));
                }
            }

            // 等待超时
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error("命令执行超时 (" + TIMEOUT_SECONDS + " 秒)，已强制终止: " + command);
            }

            int exitCode = process.exitValue();
            String out = output.toString(StandardCharsets.UTF_8);

            String result = "退出码: " + exitCode + "\n输出:\n" + (out.isEmpty() ? "(无输出)" : out);
            return ToolResult.ok(result);
        } catch (IOException e) {
            return ToolResult.error("执行命令失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("命令执行被中断");
        }
    }
}
