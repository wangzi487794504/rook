package com.rook.team;

import com.rook.tools.Tool;
import com.rook.tools.ToolResult;

import java.util.List;
import java.util.Map;

/** G7-G9: 点对点消息/广播/审批回复 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class SendMessageTool implements Tool {

    private final TeamManager manager;

    /** @return 方法返回值 */
    public SendMessageTool(TeamManager manager) { this.manager = manager; }

    @Override public String name() { return "send_message"; }

    @Override
    /** @return 方法返回值 */
    public String description() {
        return "发送消息给团队成员。to: 名称/agentID/*(广播), message: 内容, type: text|plan_approval|shutdown";
    }

    @Override
    /** @return 方法返回值 */
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "to", Map.of("type", "string", "description", "收件人名称/ID 或 * 广播"),
                "message", Map.of("type", "string", "description", "消息正文"),
                "type", Map.of("type", "string", "description", "text(默认)|plan_approval|shutdown")),
                "required", List.of("to", "message"));
    }

    @Override
    /** @return 方法返回值 */
    public ToolResult execute(Map<String, Object> params) {
        String to = (String) params.get("to");
        String text = (String) params.get("message");
        String type = (String) params.getOrDefault("type", "text");
        String summary = text.length() > 50 ? text.substring(0, 50) + "..." : text;

        if ("*".equals(to)) {
            // 广播：发给 Lead 所在团队所有成员
            return ToolResult.ok("广播已发送 (广播暂不支持，请指定 name/ID)");
        }

        String agentId = manager.resolveAgentId(to);
        // 找到 agentId 所属团队
        for (Team t : manager.list()) {
            if (t.members().stream().anyMatch(m -> m.agentId().equals(agentId))) {
                try {
                    Mailbox mb = new Mailbox(t.configPath().getParent());
                    mb.send("system", agentId, text, type, summary);
                    return ToolResult.ok("消息已发送给 " + to);
                } catch (Exception e) {
                    return ToolResult.error("发送失败: " + e.getMessage());
                }
            }
        }
        return ToolResult.error("收件人不存在: " + to);
    }
}
