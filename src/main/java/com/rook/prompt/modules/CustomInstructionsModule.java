package com.rook.prompt.modules;

import com.rook.prompt.PromptContext;
import com.rook.prompt.PromptModule;

/** 自定义指令模块 —— 用户/项目补充规则。优先级 80，可选。 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class CustomInstructionsModule implements PromptModule {
    private volatile String content = "";

    @Override public String name() { return "自定义指令"; }
    @Override public int priority() { return 80; }
    @Override
    /** @return 方法返回值 */
    public String render(PromptContext ctx) {
        return content.isBlank() ? "" : content;
    }

    public void setContent(String content) { this.content = content; }
}
