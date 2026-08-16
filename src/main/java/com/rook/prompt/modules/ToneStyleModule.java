package com.rook.prompt.modules;

import com.rook.prompt.PromptContext;
import com.rook.prompt.PromptModule;

/** 语气风格模块 —— 语言、详略、交互习惯。优先级 6 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class ToneStyleModule implements PromptModule {
    @Override public String name() { return "语气风格"; }
    @Override public int priority() { return 6; }
    @Override
    /** @return 方法返回值 */
    public String render(PromptContext ctx) {
        return """
                - 使用中文回复
                - 简洁明了，不要过度解释简单操作
                - 代码块使用正确的语言标记
                - 主动说明你的操作意图和结果""";
    }
}
