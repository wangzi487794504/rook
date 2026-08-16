package com.rook.skills;

import java.util.List;
import java.util.Map;

/** Skill 专属工具定义（来自 tool.json） */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public record SkillToolDef(
        String name,
        String description,
        Map<String, Object> inputSchema,
        List<String> command  // argv, 相对references/或绝对路径
) {}
