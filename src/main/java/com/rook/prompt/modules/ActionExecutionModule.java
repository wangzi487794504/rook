package com.rook.prompt.modules;

import com.rook.prompt.PromptContext;
import com.rook.prompt.PromptModule;

/** 动作执行模块 —— 工具调用后的处理流程。优先级 4 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class ActionExecutionModule implements PromptModule {
    @Override public String name() { return "动作执行"; }
    @Override public int priority() { return 4; }
    @Override
    /** @return 方法返回值 */
    public String render(PromptContext ctx) {
        return """
                当你调用工具后，系统会执行并返回结果。收到结果后：
                1. 分析结果是否满足需求
                2. 如需更多操作，继续调用工具
                3. 任务全部完成时，给出清晰的总结

                你可以在一轮回复中调用多个工具。只读工具（read_file、glob、grep）会并发执行，
                写入工具（write_file、edit_file、exec_command）会串行执行。""";
    }
}
