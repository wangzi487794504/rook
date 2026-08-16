package com.rook.mcp;

import com.rook.mcp.McpSession.McpToolDef;
import com.rook.tools.Tool;
import com.rook.tools.ToolResult;

import java.util.Map;

/**
 * 将远端 MCP 工具包装为 Rook 的 Tool 接口，Agent 调用时无感。
 *
 * @author wangzijie
 * @since 0.6.0
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class McpToolAdapter implements Tool {

    private final McpToolDef def;
    private final McpSession session;

    /** @return 方法返回值 */
    public McpToolAdapter(McpToolDef def, McpSession session) {
        this.def = def;
        this.session = session;
    }

    @Override
    /** @return 方法返回值 */
    public String name() {
        // 加上 Server 前缀防止与内置工具冲突
        return "mcp_" + session.getServerName() + "_" + def.name();
    }

    @Override
    /** @return 方法返回值 */
    public String description() {
        return "[MCP:" + session.getServerName() + "] " + def.description();
    }

    @Override
    /** @return 方法返回值 */
    public Map<String, Object> inputSchema() {
        return def.inputSchema();
    }

    @Override
    /** @return 方法返回值 */
    public ToolResult execute(Map<String, Object> params) {
        return session.callTool(def.name(), params);
    }
}
