package com.mewcode.tools.impl;

import com.mewcode.tools.Tool;
import com.mewcode.tools.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 改文件工具 —— 在文件中做唯一匹配替换。匹配0次或多次时报错。
 *
 * @author MewCode
 * @since 0.2.0
 */
public class EditFileTool implements Tool {

    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public String description() {
        return "[重要] 编辑文件前请先用 read_file 读取文件内容。"
                + "在文件中精确查找原文片段并替换为新文本。原文片段必须唯一匹配（精确文字匹配，非正则）。"
                + "匹配0次或多次时会报错。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "filePath", Map.of("type", "string", "description", "要修改的文件路径"),
                "oldString", Map.of("type", "string", "description", "要替换的原文片段（必须唯一匹配）"),
                "newString", Map.of("type", "string", "description", "替换后的新文本")
        ));
        schema.put("required", List.of("filePath", "oldString", "newString"));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String filePath = (String) params.get("filePath");
        String oldString = (String) params.get("oldString");
        String newString = (String) params.get("newString");

        if (filePath == null || filePath.isBlank()) return ToolResult.error("缺少参数: filePath");
        if (oldString == null) return ToolResult.error("缺少参数: oldString");
        if (newString == null) return ToolResult.error("缺少参数: newString");

        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            return ToolResult.error("文件不存在: " + filePath);
        }

        try {
            String content = Files.readString(path);

            // 精确匹配计数
            int count = 0;
            int idx = 0;
            int lastIdx = -1;
            while ((idx = content.indexOf(oldString, idx)) != -1) {
                count++;
                lastIdx = idx;
                idx += oldString.length();
            }

            if (count == 0) {
                return ToolResult.error("未找到匹配的原文片段，文件内容未修改。请检查 oldString 是否正确。");
            }

            if (count > 1) {
                return ToolResult.error("匹配到 " + count + " 处相同的原文片段，请提供更精确的上下文以确保唯一匹配。");
            }

            // 唯一匹配 —— 替换
            String newContent = content.substring(0, lastIdx)
                    + newString
                    + content.substring(lastIdx + oldString.length());

            // 原子写入
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, newContent);
            Files.move(tmp, path,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            return ToolResult.ok("文件已修改: " + filePath + "\n替换了 " + oldString.length()
                    + " → " + newString.length() + " 个字符");
        } catch (IOException e) {
            return ToolResult.error("修改文件失败: " + e.getMessage());
        }
    }
}
