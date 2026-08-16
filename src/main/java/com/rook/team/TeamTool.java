package com.rook.team;

import com.rook.tools.Tool;
import com.rook.tools.ToolResult;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** TeamCreate + TeamDelete 统一工具 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class TeamTool implements Tool {

    private final TeamManager manager;

    /** @return 方法返回值 */
    public TeamTool(TeamManager manager) { this.manager = manager; }

    @Override public String name() { return "team_manage"; }

    @Override
    /** @return 方法返回值 */
    public String description() {
        return "创建或删除团队。action: create|delete, name: 团队名称";
    }

    @Override
    /** @return 方法返回值 */
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "action", Map.of("type", "string", "description", "create 或 delete"),
                "name", Map.of("type", "string", "description", "团队名称")),
                "required", List.of("action", "name"));
    }

    @Override
    /** @return 方法返回值 */
    public ToolResult execute(Map<String, Object> params) {
        String action = (String) params.get("action");
        String name = (String) params.get("name");
        if (action == null || name == null) return ToolResult.error("缺少 action 或 name");

        try {
            return switch (action) {
                case "create" -> {
                    Team t = manager.create(name, "lead-1");
                    yield ToolResult.ok("团队已创建: " + t.name() + ", Lead 已就位");
                }
                case "delete" -> {
                    manager.delete(name);
                    yield ToolResult.ok("团队已删除: " + name);
                }
                default -> ToolResult.error("未知 action: " + action);
            };
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
