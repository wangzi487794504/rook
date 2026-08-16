package com.rook.prompt.modules;

import com.rook.prompt.PromptContext;
import com.rook.prompt.PromptModule;

/** 工具使用模块 —— 通用约定 + 关键规则双重强化。优先级 5 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class ToolUsageModule implements PromptModule {
    @Override public String name() { return "工具使用"; }
    @Override public int priority() { return 5; }
    @Override
    /** @return 方法返回值 */
    public String render(PromptContext ctx) {
        return """
                ## 通用约定

                - 优先使用专用工具完成任务，不要用文本模拟工具行为
                - 遇到不清楚的参数，查看工具的 description 和 input_schema

                ## 关键规则（请严格遵守）

                1. **编辑前必先读**：调用 edit_file 或 write_file 之前，先用 read_file 读取文件当前内容。
                   edit_file 使用精确字符串匹配（非正则），oldString 必须与文件中完全一致。
                2. **优先用专用搜索工具**：查找文件用 glob，搜索代码内容用 grep，不要用 exec_command 执行 find/grep 命令。
                3. **命令执行谨慎**：exec_command 在真实 shell 中运行，确保命令安全且不破坏系统。""";
    }
}
