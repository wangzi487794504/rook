package com.rook.prompt.modules;

import com.rook.prompt.PromptContext;
import com.rook.prompt.PromptModule;

/** 身份模块 —— Agent 名称、版本、角色定位。优先级 1 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class IdentityModule implements PromptModule {
    @Override public String name() { return "身份"; }
    @Override public int priority() { return 1; }
    @Override
    /** @return 方法返回值 */
    public String render(PromptContext ctx) {
        return "你是 Rook，一个终端 AI 编程助手（类似 Claude Code）。" +
               "你可以读取文件、编辑文件、执行命令、搜索代码，帮助用户完成编程任务。" +
               "当前版本: 0.4.0。";
    }
}
