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
import java.util.stream.Stream;

/**
 * Glob 文件搜索工具 —— 按 glob 模式匹配文件路径。
 *
 * @author MewCode
 * @since 0.2.0
 */
public class GlobTool implements Tool {

    private static final int MAX_RESULTS = 500;

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public String description() {
        return "按 glob 模式匹配文件路径。支持 ** 递归匹配。返回匹配的文件路径列表。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "pattern", Map.of("type", "string", "description", "Glob 模式，如 **/*.java"),
                "path", Map.of("type", "string", "description", "搜索的根目录，默认为当前目录")
        ));
        schema.put("required", List.of("pattern"));
        return schema;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String pattern = (String) params.get("pattern");
        if (pattern == null || pattern.isBlank()) {
            return ToolResult.error("缺少参数: pattern");
        }

        String rootPath = (String) params.getOrDefault("path", ".");
        Path root = Path.of(rootPath);

        if (!Files.exists(root)) {
            return ToolResult.error("目录不存在: " + rootPath);
        }

        try {
            // 对相对路径做匹配
            final Path rootAbs = root.toAbsolutePath().normalize();
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            final List<String> found = new ArrayList<>();

            try (Stream<Path> stream = Files.walk(rootAbs)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> matcher.matches(rootAbs.relativize(p)))
                        .limit(MAX_RESULTS + 1)
                        .forEach(p -> found.add(rootAbs.relativize(p).toString().replace('\\', '/')));
            }

            List<String> matches = new ArrayList<>(found);
            if (matches.size() > MAX_RESULTS) {
                matches = new ArrayList<>(matches.subList(0, MAX_RESULTS));
                matches.add("...(结果已截断，超过" + MAX_RESULTS + "条)");
            }

            if (matches.isEmpty()) {
                return ToolResult.ok("未找到匹配的文件");
            }

            return ToolResult.ok(String.join("\n", matches));
        } catch (IOException e) {
            return ToolResult.error("搜索失败: " + e.getMessage());
        }
    }
}
