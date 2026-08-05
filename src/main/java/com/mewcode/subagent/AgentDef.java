package com.mewcode.subagent;

import java.util.List;

/** 子Agent角色定义 */
public record AgentDef(
        String name,
        String description,
        List<String> tools,
        List<String> disallowedTools,
        String model,
        int maxRounds,
        String permissionMode,
        String isolation,    // "worktree" | null
        String prompt,
        String source
) {}
