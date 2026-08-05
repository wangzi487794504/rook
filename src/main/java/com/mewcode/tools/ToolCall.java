package com.mewcode.tools;

import java.util.Map;

/**
 * 从 LLM API 响应中解析出的工具调用请求。
 *
 * @author MewCode
 * @since 0.2.0
 */
public record ToolCall(
        /** API 返回的 tool_use id，回灌 tool_result 时需要 */
        String id,
        /** 模型想要调用的工具名称 */
        String name,
        /** 模型填写的参数 */
        Map<String, Object> input) {
}
