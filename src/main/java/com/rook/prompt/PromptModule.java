package com.rook.prompt;

/**
 * 系统提示模块 —— 每个模块有名称、优先级、渲染方法。
 * 优先级越小越靠前（1-70 为固定模块，71-100 为可选模块）。
 *
 * @author wangzijie
 * @since 0.4.0
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public interface PromptModule {

    /** 模块名称，如 "身份" */
    String name();

    /** 优先级，越小越靠前 */
    int priority();

    /** 根据上下文生成模块内容 */
    String render(PromptContext ctx);
}
