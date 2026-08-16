package com.rook.tools.impl;

import com.rook.tools.Tool;
import com.rook.tools.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读文件工具 —— 读取指定路径的文件内容，支持行范围截取。
 *
 * @author wangzijie
 * @since 0.2.0
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class ReadFileTool implements Tool {

    private static final int MAX_BYTES = 100 * 1024; // 100KB 截断

    @Override
    /** @return 方法返回值 */
    public String name() {
        return "read_file";
    }

    @Override
    /** @return 方法返回值 */
    public String description() {
        return "读取指定文件的内容。可以指定读取的起始行(offset)和行数(limit)。";
    }

    @Override
    /** @return 方法返回值 */
    public Map<String, Object> inputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "filePath", Map.of("type", "string", "description", "要读取的文件路径"),
                "offset", Map.of("type", "integer", "description", "起始行号（从1开始），可选"),
                "limit", Map.of("type", "integer", "description", "读取的行数，可选")
        ));
        schema.put("required", List.of("filePath"));
        return schema;
    }

    @Override
    /** @return 方法返回值 */
    public ToolResult execute(Map<String, Object> params) {
        String filePath = (String) params.get("filePath");
        if (filePath == null || filePath.isBlank()) {
            return ToolResult.error("缺少参数: filePath");
        }

        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            return ToolResult.error("文件不存在: " + filePath);
        }
        if (!Files.isRegularFile(path)) {
            return ToolResult.error("路径不是文件: " + filePath);
        }

        try {
            String content = Files.readString(path);
            String[] lines = content.split("\n", -1);

            // 行范围截取
            int offset = params.containsKey("offset") ? ((Number) params.get("offset")).intValue() : 1;
            int limit = params.containsKey("limit") ? ((Number) params.get("limit")).intValue() : lines.length;

            // offset 从 1 开始
            int start = Math.max(0, offset - 1);
            int end = Math.min(lines.length, start + limit);

            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(lines[i]);
                if (i < end - 1) {
                    sb.append("\n");
                }
            }

            String result = sb.toString();

            // 100KB 截断
            if (result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) {
                result = new String(
                        result.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, MAX_BYTES,
                        java.nio.charset.StandardCharsets.UTF_8)
                        + "\n...(内容已截断，超过100KB)";
            }

            return ToolResult.ok(result);
        } catch (IOException e) {
            return ToolResult.error("读取文件失败: " + e.getMessage());
        }
    }
}
