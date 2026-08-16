package com.rook.tools;

/**
 * 工具执行结果 —— 成功时 content 为结果内容，失败时 content 为错误描述。
 *
 * @author wangzijie
 * @since 0.2.0
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public record ToolResult(
        boolean success,
        String content) {

    /** 创建成功结果 */
    public static ToolResult ok(String content) {
        return new ToolResult(true, content);
    }

    /** 创建错误结果 */
    public static ToolResult error(String message) {
        return new ToolResult(false, message);
    }
}
