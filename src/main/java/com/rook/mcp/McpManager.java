package com.rook.mcp;

import com.rook.tools.ToolRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * MCP 管理器 —— 加载配置、并行初始化所有 Server、发现工具并注册。
 *
 * @author wangzijie
 * @since 0.6.0
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class McpManager {

    private final List<McpSession> sessions = new ArrayList<>();

    /**
     * 启动时调用：加载配置、初始化所有 Server、发现工具并注册。
     */
    public void discover(Path userConfig, Path projectConfig, ToolRegistry registry) {
        List<McpConfig.McpServerConfig> configs = McpConfig.load(userConfig, projectConfig);
        if (configs.isEmpty()) {
            System.out.println("[MCP] 未配置任何 MCP Server");
            return;
        }

        System.out.println("[MCP] 发现 " + configs.size() + " 个 Server，并行初始化中...");

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(configs.size(), 8));

        for (var cfg : configs) {
            executor.submit(() -> {
                try {
                    initServer(cfg, registry);
                } catch (Exception e) {
                    System.err.println("[MCP:" + cfg.name() + "] 初始化失败: " + e.getMessage());
                }
            });
        }

        executor.shutdown();
        try {
            executor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("[MCP] 初始化完成，共注册 " + countMcpTools(registry) + " 个 MCP 工具");
    }

    private void initServer(McpConfig.McpServerConfig cfg, ToolRegistry registry) {
        System.out.println("[MCP:" + cfg.name() + "] 正在连接 (" + cfg.type() + ")...");

        McpTransport transport = switch (cfg.type()) {
            case "stdio" -> {
                try {
                    yield new StdioTransport(cfg.command(), cfg.args(), cfg.env());
                } catch (IOException e) {
                    throw new RuntimeException("stdio 启动失败: " + e.getMessage(), e);
                }
            }
            case "http" -> new HttpTransport(cfg.url(), cfg.headers());
            default -> throw new IllegalArgumentException("不支持的传输类型: " + cfg.type());
        };

        McpSession session = new McpSession(cfg.name(), transport);

        List<McpSession.McpToolDef> tools = session.initialize();
        if (tools.isEmpty()) {
            System.out.println("[MCP:" + cfg.name() + "] 未发现工具，跳过");
            session.close();
            return;
        }

        for (var toolDef : tools) {
            McpToolAdapter adapter = new McpToolAdapter(toolDef, session);
            registry.register(adapter);
        }

        sessions.add(session);
        System.out.println("[MCP:" + cfg.name() + "] 已注册 " + tools.size() + " 个工具");
    }

    private int countMcpTools(ToolRegistry registry) {
        return (int) registry.names().stream()
                .filter(n -> n.startsWith("mcp_"))
                .count();
    }

    /** 动态重载 MCP 配置 */
    public void reload(Path userConfig, Path projectConfig, ToolRegistry registry) {
        // 关闭旧连接
        sessions.forEach(McpSession::close);
        sessions.clear();
        // 移除旧的 MCP 工具
        registry.names().stream()
                .filter(n -> n.startsWith("mcp_"))
                .forEach(n -> registry.unregister(n));
        // 重新发现
        discover(userConfig, projectConfig, registry);
    }

    public void shutdown() {
        sessions.forEach(McpSession::close);
    }
}
