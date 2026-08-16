package com.rook.mcp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * stdio 传输 —— 启动子进程，通过 stdin/stdout 收发消息。
 *
 * @author wangzijie
 * @since 0.6.0
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class StdioTransport implements McpTransport {

    private final Process process;
    private final BufferedWriter writer;
    private final BufferedReader reader;

    /** @return 方法返回值 */
    public StdioTransport(String command, List<String> args, Map<String, String> env) throws IOException {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add(command);
        cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().putAll(env);
        pb.redirectErrorStream(false);
        this.process = pb.start();

        this.writer = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8));
        this.reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public synchronized String send(String jsonRpcRequest) {
        try {
            // 提取请求 id
            String reqId = jsonRpcRequest.contains("\"id\"")
                    ? jsonRpcRequest.replaceAll(".*\"id\"\\s*:\\s*(\\d+).*", "$1") : "";

            writer.write(jsonRpcRequest);
            writer.newLine();
            writer.flush();

            // 读取响应行，按 id 精确匹配（跳过服务器主动推送的通知）
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("\"id\"")) {
                    String respId = line.replaceAll(".*\"id\"\\s*:\\s*(\\d+).*", "$1");
                    if (respId.equals(reqId)) return line;
                }
                // 无 id 的通知行 或 id 不匹配 → 跳过
            }

            // readLine 返回 null = 进程已退出
            try (BufferedReader err = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String errLine = err.readLine();
                return "{\"error\":{\"code\":-1,\"message\":\"进程已退出: "
                        + process.exitValue() + (errLine != null ? " - " + errLine : "") + "\"}}";
            }
        } catch (IOException e) {
            return "{\"error\":{\"code\":-1,\"message\":\"" + e.getMessage() + "\"}}";
        }
    }

    @Override
    public void close() {
        process.destroy();
    }
}
