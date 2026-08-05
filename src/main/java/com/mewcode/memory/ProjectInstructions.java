package com.mewcode.memory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 项目指令文件加载器 —— 三层 CLAUDE.md + @include 支持。
 *
 * @author MewCode
 * @since 0.8.0
 */
public class ProjectInstructions {

    private static final Pattern INCLUDE_PAT = Pattern.compile("@include\\s+(\\S+)");
    private static final int MAX_DEPTH = 3;

    /**
     * 三层加载，高优先级在前。
     * @return 拼接后的完整指令文本
     */
    public static String load(Path projectRoot, Path userHome) {
        List<Path> paths = List.of(
                projectRoot.resolve("CLAUDE.md"),                    // 最高优先
                projectRoot.resolve(".mewcode/CLAUDE.md"),
                userHome.resolve(".mewcode/CLAUDE.md")              // 最低优先
        );

        StringBuilder sb = new StringBuilder();
        for (Path p : paths) {
            if (Files.exists(p)) {
                try {
                    String content = resolve(p, projectRoot, new HashSet<>(), 0);
                    if (!content.isBlank()) {
                        if (!sb.isEmpty()) sb.append("\n\n");
                        sb.append(content);
                    }
                } catch (IOException e) {
                    System.err.println("[Memory] 加载失败: " + p + " - " + e.getMessage());
                }
            }
        }
        return sb.toString();
    }

    /** 递归解析 @include */
    static String resolve(Path file, Path projectRoot, Set<Path> visited, int depth)
            throws IOException {
        if (depth > MAX_DEPTH) return "";
        Path abs = file.toAbsolutePath().normalize();
        if (!visited.add(abs)) return ""; // 防环
        // 安全检查：必须在项目目录内
        if (!abs.startsWith(projectRoot.toAbsolutePath().normalize())) return "";

        String content = Files.readString(abs);
        StringBuilder result = new StringBuilder();
        Matcher m = INCLUDE_PAT.matcher(content);
        int lastEnd = 0;
        while (m.find()) {
            result.append(content, lastEnd, m.start());
            String includePath = m.group(1);
            // 禁止 ../ 跳出
            if (includePath.contains("..")) continue;
            Path incFile = abs.getParent().resolve(includePath).normalize();
            try {
                String inc = resolve(incFile, projectRoot, visited, depth + 1);
                result.append(inc);
            } catch (IOException ignored) {}
            lastEnd = m.end();
        }
        result.append(content, lastEnd, content.length());
        return result.toString();
    }
}
