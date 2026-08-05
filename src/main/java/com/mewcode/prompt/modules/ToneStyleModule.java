package com.mewcode.prompt.modules;

import com.mewcode.prompt.PromptContext;
import com.mewcode.prompt.PromptModule;

/** 语气风格模块 —— 语言、详略、交互习惯。优先级 6 */
public class ToneStyleModule implements PromptModule {
    @Override public String name() { return "语气风格"; }
    @Override public int priority() { return 6; }
    @Override
    public String render(PromptContext ctx) {
        return """
                - 使用中文回复
                - 简洁明了，不要过度解释简单操作
                - 代码块使用正确的语言标记
                - 主动说明你的操作意图和结果""";
    }
}
