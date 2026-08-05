package com.mewcode.prompt;

import java.util.List;

/**
 * 模块渲染上下文 —— 携带 PromptModule 渲染所需的环境信息。
 *
 * @author MewCode
 * @since 0.4.0
 */
public record PromptContext(
        boolean planMode,
        int iteration,
        String workingDir,
        String os,
        List<String> activeSkills) {

    public PromptContext {
        if (os == null) os = System.getProperty("os.name");
        if (workingDir == null) workingDir = System.getProperty("user.dir");
        if (activeSkills == null) activeSkills = List.of();
    }
}
