package com.rook.permission;

import java.util.Map;
import java.util.Set;

/**
 * 四档权限模式。读/写/执行三类工具各自对应 Allow 或 Ask 的兜底裁决。
 *
 * @author wangzijie
 * @since 0.5.0
 */
public enum PermissionMode {
    DEFAULT(Map.of(
            ToolCategory.READ, PermissionVerdict.ALLOW,
            ToolCategory.WRITE, PermissionVerdict.ASK,
            ToolCategory.EXEC, PermissionVerdict.ASK)),
    ACCEPT_EDITS(Map.of(
            ToolCategory.READ, PermissionVerdict.ALLOW,
            ToolCategory.WRITE, PermissionVerdict.ALLOW,
            ToolCategory.EXEC, PermissionVerdict.ASK)),
    PLAN(Map.of(
            ToolCategory.READ, PermissionVerdict.ALLOW,
            ToolCategory.WRITE, PermissionVerdict.DENY,
            ToolCategory.EXEC, PermissionVerdict.DENY)),
    BYPASS(Map.of(
            ToolCategory.READ, PermissionVerdict.ALLOW,
            ToolCategory.WRITE, PermissionVerdict.ALLOW,
            ToolCategory.EXEC, PermissionVerdict.ALLOW));

    private static final Set<String> READ_TOOLS = Set.of("read_file", "glob", "grep", "web_search");
    private static final Set<String> WRITE_TOOLS = Set.of("write_file", "edit_file");
    private static final Set<String> EXEC_TOOLS = Set.of("exec_command");

    /** MCP 工具默认分类：以 mcp_ 开头，按已知服务分类 */
    /** @return 方法返回值 */
    public PermissionVerdict verdictFor(String toolName) {
        if (READ_TOOLS.contains(toolName)) return map.get(ToolCategory.READ);
        if (WRITE_TOOLS.contains(toolName)) return map.get(ToolCategory.WRITE);
        if (EXEC_TOOLS.contains(toolName)) return map.get(ToolCategory.EXEC);
        // MCP 搜索类工具默认视为只读
        if (toolName.startsWith("mcp_onesearch_")) return map.get(ToolCategory.READ);
        return PermissionVerdict.ASK;
    }

    private final Map<ToolCategory, PermissionVerdict> map;

    PermissionMode(Map<ToolCategory, PermissionVerdict> map) { this.map = map; }

    /** 从字符串解析模式名，不区分大小写 */
    public static PermissionMode fromString(String s) {
        return switch (s.toLowerCase()) {
            case "default" -> DEFAULT;
            case "acceptedits", "accept-edits" -> ACCEPT_EDITS;
            case "plan" -> PLAN;
            case "bypass", "bypasspermissions", "bypass-permissions" -> BYPASS;
            default -> DEFAULT;
        };
    }
}
