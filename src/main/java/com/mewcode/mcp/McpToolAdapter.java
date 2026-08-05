package com.mewcode.mcp;

import com.mewcode.mcp.McpSession.McpToolDef;
import com.mewcode.tools.Tool;
import com.mewcode.tools.ToolResult;

import java.util.Map;

/**
 * 将远端 MCP 工具包装为 MewCode 的 Tool 接口，Agent 调用时无感。
 *
 * @author MewCode
 * @since 0.6.0
 */
public class McpToolAdapter implements Tool {

    private final McpToolDef def;
    private final McpSession session;

    public McpToolAdapter(McpToolDef def, McpSession session) {
        this.def = def;
        this.session = session;
    }

    @Override
    public String name() {
        // 加上 Server 前缀防止与内置工具冲突
        return "mcp_" + session.getServerName() + "_" + def.name();
    }

    @Override
    public String description() {
        return "[MCP:" + session.getServerName() + "] " + def.description();
    }

    @Override
    public Map<String, Object> inputSchema() {
        return def.inputSchema();
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        return session.callTool(def.name(), params);
    }
}
