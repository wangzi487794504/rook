package com.mewcode.prompt;

import java.util.*;

/**
 * System Prompt 装配器 —— 按优先级排序模块并拼接输出。
 *
 * @author MewCode
 * @since 0.4.0
 */
public class PromptBuilder {

    private final List<PromptModule> modules = new ArrayList<>();

    /**
     * 注册一个模块。相同优先级的模块按注册顺序排列。
     */
    public void register(PromptModule module) {
        modules.add(module);
    }

    /**
     * 按优先级排序后拼装完整 System Prompt。
     */
    public String build(PromptContext ctx) {
        return modules.stream()
                .sorted(Comparator.comparingInt(PromptModule::priority))
                .map(m -> "## " + m.name() + "\n" + m.render(ctx))
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("");
    }

    /**
     * 返回按优先级排序的模块列表（不可变）。
     */
    public List<PromptModule> getModules() {
        return modules.stream()
                .sorted(Comparator.comparingInt(PromptModule::priority))
                .toList();
    }
}
