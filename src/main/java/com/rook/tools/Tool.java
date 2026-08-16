package com.rook.tools;

import java.util.Map;

/**
 * 工具抽象接口 —— 所有工具实现此接口。
 * 每个工具包含元信息（名称、描述、参数 Schema）和执行方法。
 *
 * @author wangzijie
 * @since 0.2.0
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public interface Tool {

    /**
     * 工具名称，如 "read_file"，模型通过此名称选择工具。
     */
    String name();

    /**
     * 工具功能描述，模型读取此描述决定是否使用该工具。
     */
    String description();

    /**
     * 参数 JSON Schema（Draft 7），定义工具接收的参数格式。
     * 模型根据此 Schema 填充参数值。
     */
    Map<String, Object> inputSchema();

    /**
     * 执行工具，接收模型填充的参数，返回结构化结果。
     *
     * @param params 模型填入的参数
     * @return 执行结果（成功内容或错误描述）
     */
    ToolResult execute(Map<String, Object> params);
}
