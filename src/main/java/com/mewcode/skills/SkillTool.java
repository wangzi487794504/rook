package com.mewcode.skills;

import com.mewcode.cmd.Command;
import com.mewcode.cmd.CommandRegistry;
import com.mewcode.cmd.CommandType;
import com.mewcode.tools.Tool;
import com.mewcode.tools.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * 内置系统工具 —— skill_load。激活 Skill + 自动注册斜杠命令。
 */
public class SkillTool implements Tool {

    private final SkillRegistry registry;
    private final CommandRegistry cmdRegistry;

    public SkillTool(SkillRegistry registry, CommandRegistry cmdRegistry) {
        this.registry = registry;
        this.cmdRegistry = cmdRegistry;
    }

    @Override public String name() { return "skill_load"; }

    @Override
    public String description() {
        return "激活一个已注册的 Skill，自动注册为斜杠命令。参数: name(Skill名称), args(可选参数)";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "name", Map.of("type", "string", "description", "Skill名称"),
                        "args", Map.of("type", "string", "description", "传入参数, 可选")),
                "required", List.of("name"));
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String name = (String) params.get("name");
        String args = (String) params.getOrDefault("args", "");

        if (name == null || name.isBlank()) {
            return ToolResult.error("缺少参数: name");
        }

        var skill = registry.get(name);
        if (skill.isEmpty()) {
            return ToolResult.error("Skill 不存在: " + name
                    + "。可用: " + registry.all().stream().map(SkillDef::name).toList());
        }

        SkillDef s = skill.get();
        registry.activate(name);

        // ★ 自动注册斜杠命令
        registerSlashCommand(s);

        // 替换占位符
        String body = s.body();
        if (body.contains("$ARGUMENTS")) {
            body = body.replace("$ARGUMENTS", args != null ? args : "");
        } else if (args != null && !args.isBlank()) {
            body += "\n\n## User Request\n\n" + args;
        }

        return ToolResult.ok("Skill [" + s.name() + "] 已激活。输入 /" + s.name() + " 使用。\n\n" + body);
    }

    /** 将 Skill 注册为斜杠命令 */
    private void registerSlashCommand(SkillDef s) {
        // 检查是否已注册，避免重复
        if (cmdRegistry.get(s.name()).isPresent()) return;

        Command cmd = new Command(s.name(), List.of(), s.description(),
                "/" + s.name(), CommandType.PROMPT_FEED, false,
                (console, cmdArgs) -> {
                    // PROMPT_FEED: 将 Skill 指令作为消息注入
                    String body = s.body();
                    if (body.contains("$ARGUMENTS")) {
                        body = body.replace("$ARGUMENTS", cmdArgs != null ? cmdArgs : "");
                    } else if (cmdArgs != null && !cmdArgs.isBlank()) {
                        body += "\n\n## User Request\n\n" + cmdArgs;
                    }
                    return body;
                });
        try {
            cmdRegistry.register(cmd);
        } catch (IllegalStateException e) {
            // 同名命令已存在，跳过
        }
    }
}
