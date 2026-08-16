package com.rook.mcp;

import com.rook.tools.ToolResult;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 单个 MCP Server 的会话 —— 三阶段（握手→列工具→调用），内部串行队列。
 *
 * @author wangzijie
 * @since 0.6.0
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class McpSession {

    private final String serverName;
    private final McpTransport transport;
    private final BlockingQueue<Runnable> sendQueue = new LinkedBlockingQueue<>();
    private volatile boolean initialized = false;

    /** @return 方法返回值 */
    public McpSession(String serverName, McpTransport transport) {
        this.serverName = serverName;
        this.transport = transport;
        // 启动消费线程
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    sendQueue.take().run();
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "mcp-" + serverName);
        t.setDaemon(true);
        t.start();
    }

    /** 发送请求并等待响应（在消费线程内执行） */
    private JsonRpc.JsonRpcResponse blockingSend(String method, Map<String, Object> params) {
        try {
            int id = JsonRpc.nextId();
            String req = JsonRpc.buildRequest(id, method, params);
            String rawResp = transport.send(req);
            return JsonRpc.parseResponse(rawResp);
        } catch (Exception e) {
            return JsonRpc.JsonRpcResponse.error(-1, e.getMessage());
        }
    }

    /** 工具定义（从 tools/list 返回） */
    @SuppressWarnings("unchecked")
    public record McpToolDef(String name, String description, Map<String, Object> inputSchema) {}

    /** 初始化握手 */
    @SuppressWarnings("unchecked")
    /** @return 方法返回值 */
    public List<McpToolDef> initialize() {
        try {
            // Step 1: initialize
            Map<String, Object> initParams = Map.of(
                    "protocolVersion", "2024-11-05",
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "Rook", "version", "0.6.0"));
            JsonRpc.JsonRpcResponse initResp = blockingSend("initialize", initParams);
            if (!initResp.success()) {
                System.err.println("[MCP:" + serverName + "] 握手失败: " + initResp.errorMessage());
                return List.of();
            }

            // Step 2: notifications/initialized
            int id = JsonRpc.nextId();
            String notifyReq = JsonRpc.buildRequest(id, "notifications/initialized", Map.of());
            transport.send(notifyReq);

            initialized = true;

            // Step 3: tools/list
            JsonRpc.JsonRpcResponse toolsResp = blockingSend("tools/list", Map.of());
            if (!toolsResp.success()) {
                System.err.println("[MCP:" + serverName + "] 列工具失败: " + toolsResp.errorMessage());
                return List.of();
            }

            Map<String, Object> result = (Map<String, Object>) toolsResp.result();
            List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
            if (tools == null) return List.of();

            return tools.stream()
                    .map(t -> new McpToolDef(
                            String.valueOf(t.get("name")),
                            String.valueOf(t.getOrDefault("description", "")),
                            (Map<String, Object>) t.getOrDefault("inputSchema", Map.of())))
                    .toList();
        } catch (Exception e) {
            System.err.println("[MCP:" + serverName + "] 初始化异常: " + e.getMessage());
            return List.of();
        }
    }

    /** 调用工具（在消费线程内执行） */
    @SuppressWarnings("unchecked")
    /** @return 方法返回值 */
    public ToolResult callTool(String toolName, Map<String, Object> params) {
        if (!initialized) {
            return ToolResult.error("MCP Server 未初始化: " + serverName);
        }

        try {
            Map<String, Object> callParams = Map.of("name", toolName, "arguments", params);
            JsonRpc.JsonRpcResponse resp = blockingSend("tools/call", callParams);

            if (!resp.success()) {
                return ToolResult.error("MCP工具调用失败: " + resp.errorMessage());
            }

            // 提取 content
            Map<String, Object> result = (Map<String, Object>) resp.result();
            List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
            if (content != null) {
                StringBuilder sb = new StringBuilder();
                for (var block : content) {
                    String type = (String) block.get("type");
                    if ("text".equals(type)) {
                        sb.append(block.getOrDefault("text", ""));
                    }
                }
                return ToolResult.ok(sb.toString());
            }
            return ToolResult.ok(Objects.toString(resp.result()));
        } catch (Exception e) {
            return ToolResult.error("MCP调用异常: " + e.getMessage());
        }
    }

    public void close() {
        transport.close();
    }

    /** @return 方法返回值 */
    public String getServerName() { return serverName; }
}
