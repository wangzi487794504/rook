package com.mewcode.prompt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PromptBuilderTest {

    @Test
    void should_按优先级排列_When_注册多个模块() {
        PromptBuilder builder = new PromptBuilder();
        builder.register(new StubModule("C", 10));
        builder.register(new StubModule("A", 1));
        builder.register(new StubModule("B", 5));

        List<PromptModule> sorted = builder.getModules();
        assertEquals("A", sorted.get(0).name());
        assertEquals("B", sorted.get(1).name());
        assertEquals("C", sorted.get(2).name());
    }

    @Test
    void should_输出含所有模块_When_build() {
        PromptBuilder builder = new PromptBuilder();
        builder.register(new StubModule("身份", 1));
        builder.register(new StubModule("工具使用", 5, "规则内容"));

        String result = builder.build(new PromptContext(false, 1, "/tmp", "Linux", List.of()));
        assertTrue(result.contains("## 身份"));
        assertTrue(result.contains("工具使用"));
        assertTrue(result.contains("规则内容"));
        assertTrue(result.contains("\n\n")); // 模块间空行分隔
    }

    @Test
    void should_新增模块不改Builder代码_When_直接register() {
        PromptBuilder builder = new PromptBuilder();
        builder.register(new StubModule("现有模块", 5));
        builder.register(new StubModule("新模块", 50));  // 直接注册，不改 Builder

        assertEquals(2, builder.getModules().size());
    }

    @Test
    void should_可选模块排在固定模块之后_When_混合注册() {
        PromptBuilder builder = new PromptBuilder();
        builder.register(new StubModule("可选B", 90));
        builder.register(new StubModule("固定A", 10));
        builder.register(new StubModule("固定B", 20));

        List<PromptModule> sorted = builder.getModules();
        assertEquals("固定A", sorted.get(0).name());
        assertEquals("固定B", sorted.get(1).name());
        assertEquals("可选B", sorted.get(2).name());
    }

    record StubModule(String name, int priority, String content) implements PromptModule {
        StubModule(String name, int priority) { this(name, priority, name + " 的内容"); }
        @Override public String render(PromptContext ctx) { return content; }
    }
}
