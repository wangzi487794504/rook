package com.rook.tools.impl;

import com.rook.tools.Tool;
import com.rook.tools.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 写文件工具 —— 创建或覆盖写入文件，原子操作。
 *
 * @author wangzijie
 * @since 0.2.0
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class WriteFileTool implements Tool {

    @Override
    /** @return 方法返回值 */
    public String name() {
        return "write_file";
    }

    @Override
    /** @return 方法返回值 */
    public String description() {
        return "创建新文件或覆盖写入已有文件。会自动创建不存在的父目录。";
    }

    @Override
    /** @return 方法返回值 */
    public Map<String, Object> inputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "filePath", Map.of("type", "string", "description", "要写入的文件路径"),
                "content", Map.of("type", "string", "description", "要写入的文件内容")
        ));
        schema.put("required", List.of("filePath", "content"));
        return schema;
    }

    @Override
    /** @return 方法返回值 */
    public ToolResult execute(Map<String, Object> params) {
        String filePath = (String) params.get("filePath");
        String content = (String) params.get("content");

        if (filePath == null || filePath.isBlank()) {
            return ToolResult.error("缺少参数: filePath");
        }
        if (content == null) {
            return ToolResult.error("缺少参数: content");
        }

        Path target = Path.of(filePath);

        try {
            // 确保父目录存在
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            // 原子写入：先写临时文件，再 move
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, content);

            // 覆盖原文件
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

            return ToolResult.ok("文件已写入: " + filePath + " (" + content.length() + " 字符)");
        } catch (IOException e) {
            return ToolResult.error("写入文件失败: " + e.getMessage());
        }
    }
}
