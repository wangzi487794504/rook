package com.mewcode.permission;

/** 单条可配置规则 */
public record RuleConfig(
        String toolName,   // 工具名，如 "Bash"
        String pattern,    // 参数模式，如 "git *"，null 表示匹配所有
        boolean allow,     // true=allow, false=deny
        String source      // 来源文件路径
) {}
