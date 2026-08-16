package com.rook.team;

import com.rook.tools.Tool;
import com.rook.tools.ToolResult;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** TaskCreate + TaskList + TaskGet + TaskUpdate 四个工具 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class TaskTools {

    public static Tool createTool(TaskBoard board) {
        return new Tool() {
            @Override public String name() { return "task_create"; }
            @Override public String description() { return "创建新任务。subject: 标题, description: 详情"; }
            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties", Map.of(
                        "subject", Map.of("type", "string"),
                        "description", Map.of("type", "string")),
                        "required", List.of("subject", "description"));
            }
            @Override
            public ToolResult execute(Map<String, Object> params) {
                var t = board.create((String) params.get("subject"), (String) params.get("description"));
                try { board.save(); } catch (IOException e) { return ToolResult.error(e.getMessage()); }
                return ToolResult.ok("任务 " + t.id + " 已创建: " + t.subject);
            }
        };
    }

    public static Tool listTool(TaskBoard board) {
        return new Tool() {
            @Override public String name() { return "task_list"; }
            @Override public String description() { return "列出所有任务"; }
            @Override public Map<String, Object> inputSchema() { return Map.of("type", "object", "properties", Map.of()); }
            @Override
            public ToolResult execute(Map<String, Object> params) {
                var tasks = board.list();
                if (tasks.isEmpty()) return ToolResult.ok("无任务");
                StringBuilder sb = new StringBuilder();
                for (var t : tasks) {
                    sb.append("- [").append(t.status).append("] ").append(t.id)
                            .append(": ").append(t.subject).append("\n");
                }
                return ToolResult.ok(sb.toString());
            }
        };
    }

    public static Tool getTool(TaskBoard board) {
        return new Tool() {
            @Override public String name() { return "task_get"; }
            @Override public String description() { return "获取任务详情。id: 任务ID"; }
            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties",
                        Map.of("id", Map.of("type", "string")), "required", List.of("id"));
            }
            @Override
            public ToolResult execute(Map<String, Object> params) {
                var t = board.get((String) params.get("id"));
                return t.map(task -> ToolResult.ok(
                        "[" + task.status + "] " + task.subject + "\n" + task.description
                        + "\n依赖: " + task.blockedBy + " | 被依赖: " + task.blocks))
                        .orElse(ToolResult.error("任务不存在"));
            }
        };
    }

    public static Tool updateTool(TaskBoard board) {
        return new Tool() {
            @Override public String name() { return "task_update"; }
            @Override public String description() {
                return "更新任务。id: 任务ID, status: pending|in_progress|completed, addBlocks: [依赖此任务的其他ID], addBlockedBy: [此任务依赖的其他ID]";
            }
            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties", Map.of(
                        "id", Map.of("type", "string"),
                        "status", Map.of("type", "string"),
                        "addBlocks", Map.of("type", "array", "items", Map.of("type", "string")),
                        "addBlockedBy", Map.of("type", "array", "items", Map.of("type", "string"))),
                        "required", List.of("id"));
            }
            @Override
            @SuppressWarnings("unchecked")
            public ToolResult execute(Map<String, Object> params) {
                var opt = board.get((String) params.get("id"));
                if (opt.isEmpty()) return ToolResult.error("任务不存在");
                var t = opt.get();
                if (params.containsKey("status")) t.status = (String) params.get("status");
                List<String> blocks = (List<String>) params.get("addBlocks");
                if (blocks != null) t.blocks.addAll(blocks);
                List<String> blockedBy = (List<String>) params.get("addBlockedBy");
                if (blockedBy != null) t.blockedBy.addAll(blockedBy);
                board.update(t);
                try { board.save(); } catch (IOException e) { return ToolResult.error(e.getMessage()); }
                return ToolResult.ok("任务 " + t.id + " 已更新");
            }
        };
    }
}
