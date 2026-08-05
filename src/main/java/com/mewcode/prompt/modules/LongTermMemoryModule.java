package com.mewcode.prompt.modules;

import com.mewcode.prompt.PromptContext;
import com.mewcode.prompt.PromptModule;

/** 长期记忆模块 —— 跨会话持久化的关键信息。优先级 90，可选。 */
public class LongTermMemoryModule implements PromptModule {
    private volatile String content = "";

    @Override public String name() { return "长期记忆"; }
    @Override public int priority() { return 90; }
    @Override
    public String render(PromptContext ctx) {
        return content.isBlank() ? "" : content;
    }

    public void setContent(String content) { this.content = content; }
}
