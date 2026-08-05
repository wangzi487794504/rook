package com.mewcode.prompt.modules;

import com.mewcode.prompt.PromptContext;
import com.mewcode.prompt.PromptModule;

/** 文本输出模块 —— 输出格式约束。优先级 7 */
public class TextOutputModule implements PromptModule {
    @Override public String name() { return "文本输出"; }
    @Override public int priority() { return 7; }
    @Override
    public String render(PromptContext ctx) {
        return """
                - 代码块使用三个反引号包裹，并标注语言类型（如 ```java、```bash）
                - 文件路径使用反引号包裹，如 `src/main/java/Main.java`
                - 不要输出过长的内容，必要时截断并标注""";
    }
}
