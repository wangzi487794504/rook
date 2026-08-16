package com.rook.prompt.modules;

import com.rook.prompt.PromptContext;
import com.rook.prompt.PromptModule;

/** 任务模式模块 —— 当前工作模式及对应行为。优先级 3 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class TaskModeModule implements PromptModule {
    @Override public String name() { return "任务模式"; }
    @Override public int priority() { return 3; }
    @Override
    /** @return 方法返回值 */
    public String render(PromptContext ctx) {
        if (ctx.planMode()) {
            return """
                    你当前处于**计划模式**。
                    - 只能使用只读工具（read_file、glob、grep）探索和分析代码
                    - 不能修改文件、不能执行命令
                    - 请专注于分析问题、制定详细的执行计划
                    - 计划完成后，提示用户使用 `:do` 命令切换回执行模式""";
        }
        return "你当前处于**正常执行模式**。所有工具均可用。先思考再行动，必要时使用 Agent Loop 多步完成复杂任务。";
    }
}
