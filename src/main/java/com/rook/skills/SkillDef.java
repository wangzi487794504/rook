package com.rook.skills;

import java.util.List;
import java.util.Map;

/** 一个 Skill 的完整定义 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public record SkillDef(
        String name,              // [a-z][a-z0-9-]*, 1-32
        String description,       // ≤120字符
        List<String> allowedTools,// 白名单, 空=不限制
        String mode,              // "inline" | "fork"
        String forkContext,       // "none"|"recent"|"full", fork模式用
        String model,             // 可选模型
        String body,              // Markdown正文
        Map<String, SkillToolDef> tools, // 专属工具 name→def
        String sourcePath         // 来源目录
) {
    public boolean isInline() { return !"fork".equals(mode); }
}
