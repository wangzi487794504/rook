package com.mewcode.subagent;

import com.mewcode.llm.*;
import com.mewcode.tools.*;

import java.nio.file.Path;
import java.util.*;

/**
 * 统一Agent工具 —— type参数分流 defined/fork。
 */
public class AgentTool implements Tool {

    private final LlmProvider provider;
    private final ToolRegistry tools;
    private final List<String> globalDisallowed;
    private final List<ChatMessage> parentHistory;
    private final AgentDefLoader loader;
    private final Path builtinDir;
    private final Path userDir;
    private final Path projectDir;
    private final Path repoRoot;

    public AgentTool(LlmProvider provider, ToolRegistry tools,
                     List<String> globalDisallowed, List<ChatMessage> parentHistory,
                     AgentDefLoader loader, Path builtinDir, Path userDir, Path projectDir,
                     Path repoRoot) {
        this.provider = provider;
        this.tools = tools;
        this.globalDisallowed = globalDisallowed;
        this.parentHistory = new ArrayList<>(parentHistory);
        this.loader = loader;
        this.builtinDir = builtinDir;
        this.userDir = userDir;
        this.projectDir = projectDir;
        this.repoRoot = repoRoot;
    }

    @Override public String name() { return "agent"; }

    @Override
    public String description() {
        return "启动一个子Agent执行独立任务。参数: subagent_type(角色名,定义式)或留空(fork式), description(任务描述), prompt(任务详情)";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "subagent_type", Map.of("type", "string", "description", "预定义角色名,不填走fork"),
                "description", Map.of("type", "string", "description", "简短任务描述(3-5字)"),
                "prompt", Map.of("type", "string", "description", "完整任务指令")),
                "required", List.of("description", "prompt"));
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String agentType = (String) params.get("subagent_type");
        String task = (String) params.get("prompt");
        String desc = (String) params.getOrDefault("description", "task");

        String mode = (agentType != null && !agentType.isBlank()) ? "defined" : "fork";

        try {
            var future = SubAgent.run(mode, agentType, task, parentHistory,
                    provider, tools, globalDisallowed,
                    loader, builtinDir, userDir, projectDir, repoRoot);
            var result = future.get(); // 同步等待

            if (!result.success()) {
                return ToolResult.error(result.error());
            }
            return ToolResult.ok(result.output());
        } catch (Exception e) {
            return ToolResult.error("子Agent异常: " + e.getMessage());
        }
    }
}
