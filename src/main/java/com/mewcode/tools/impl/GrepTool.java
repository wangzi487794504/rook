package com.mewcode.tools.impl;

import com.mewcode.tools.Tool;
import com.mewcode.tools.ToolResult;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Grep 代码搜索工具 —— 在文件中按正则搜索内容。
 *
 * @author MewCode
 * @since 0.2.0
 */
public class GrepTool implements Tool {

    private static final int MAX_RESULTS = 200;

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String description() {
        return "在文件内容中按正则表达式搜索文本。返回匹配的文件路径、行号和内容。支持文件名过滤和大小写控制。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "pattern", Map.of("type", "string", "description", "正则表达式搜索模式"),
                "path", Map.of("type", "string", "description", "搜索的根目录，默认为当前目录"),
                "include", Map.of("type", "string",
                        "description", "文件名过滤 glob，如 *.java，可选"),
                "ignoreCase", Map.of("type", "boolean",
                        "description", "是否忽略大小写，默认 true")
        ));
        schema.put("required", List.of("pattern"));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String patternStr = (String) params.get("pattern");
        if (patternStr == null || patternStr.isBlank()) {
            return ToolResult.error("缺少参数: pattern");
        }

        String rootPath = (String) params.getOrDefault("path", ".");
        String include = (String) params.get("include");
        boolean ignoreCase = params.containsKey("ignoreCase")
                ? ((Boolean) params.get("ignoreCase")) : true;

        Path root = Path.of(rootPath);
        if (!Files.exists(root)) {
            return ToolResult.error("目录不存在: " + rootPath);
        }

        try {
            int flags = ignoreCase ? Pattern.CASE_INSENSITIVE : 0;
            Pattern regex = Pattern.compile(patternStr, flags);
            List<String> results = new ArrayList<>();

            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> include == null
                                || FileSystems.getDefault()
                                        .getPathMatcher("glob:" + include)
                                        .matches(p.getFileName()))
                        .forEach(file -> {
                            if (results.size() >= MAX_RESULTS) return;
                            try {
                                List<String> lines = Files.readAllLines(file);
                                for (int i = 0; i < lines.size() && results.size() < MAX_RESULTS; i++) {
                                    if (regex.matcher(lines.get(i)).find()) {
                                        String relPath = root.relativize(file).toString()
                                                .replace('\\', '/');
                                        results.add(relPath + ":" + (i + 1) + ":" + lines.get(i));
                                    }
                                }
                            } catch (IOException ignored) {
                                // 跳过不可读的文件
                            }
                        });
            }

            if (results.size() >= MAX_RESULTS) {
                results.add("...(结果已截断，超过" + MAX_RESULTS + "条)");
            }

            if (results.isEmpty()) {
                return ToolResult.ok("未找到匹配的内容");
            }

            return ToolResult.ok(String.join("\n", results));
        } catch (PatternSyntaxException e) {
            return ToolResult.error("正则表达式语法错误: " + e.getMessage());
        } catch (IOException e) {
            return ToolResult.error("搜索失败: " + e.getMessage());
        }
    }
}
