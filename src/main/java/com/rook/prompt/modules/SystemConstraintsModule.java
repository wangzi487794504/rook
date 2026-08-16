package com.rook.prompt.modules;

import com.rook.prompt.PromptContext;
import com.rook.prompt.PromptModule;

/** 系统约束模块 —— 硬性规则。优先级 2 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class SystemConstraintsModule implements PromptModule {
    @Override public String name() { return "系统约束"; }
    @Override public int priority() { return 2; }
    @Override
    /** @return 方法返回值 */
    public String render(PromptContext ctx) {
        return """
                - 不要编造信息。如果不确定，使用工具查看真实内容而非猜测。
                - 不要跳过必要步骤。需要多步操作时，一步步来。
                - 遇到错误不要放弃。分析错误原因，调整后重试。
                - 工具调用失败时，将错误信息如实报告给用户，不要隐瞒。""";
    }
}
