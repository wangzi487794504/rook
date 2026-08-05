package com.mewcode.prompt.modules;

import com.mewcode.prompt.PromptContext;
import com.mewcode.prompt.PromptModule;

/** 已激活 Skill 模块 —— 当前会话启用的技能列表。优先级 85，可选。 */
public class ActiveSkillsModule implements PromptModule {
    @Override public String name() { return "已激活 Skill"; }
    @Override public int priority() { return 85; }
    @Override
    public String render(PromptContext ctx) {
        if (ctx.activeSkills().isEmpty()) return "";
        return "当前激活的 Skill: " + String.join(", ", ctx.activeSkills());
    }
}
