package com.rook.prompt.modules;

import com.rook.prompt.PromptContext;
import com.rook.prompt.PromptModule;

/** 长期记忆模块 —— 跨会话持久化的关键信息。优先级 90，可选。 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class LongTermMemoryModule implements PromptModule {
    private volatile String content = "";

    @Override public String name() { return "长期记忆"; }
    @Override public int priority() { return 90; }
    @Override
    /** @return 方法返回值 */
    public String render(PromptContext ctx) {
        return content.isBlank() ? "" : content;
    }

    public void setContent(String content) { this.content = content; }
}
