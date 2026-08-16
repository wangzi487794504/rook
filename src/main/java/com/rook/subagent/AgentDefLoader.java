package com.rook.subagent;

import com.rook.util.ClasspathScanner;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 角色定义加载器 —— 内置从 classpath 加载，用户/项目从文件系统加载，同名覆盖。
 *
 * @author wangzijie
 * @since 0.1.0
 */
public class AgentDefLoader {

    /** 内置 SubAgent 在 classpath 中的根路径（打包后位于 fat jar 内） */
    private static final String BUILTIN_RESOURCE = "com/rook/subagent/builtin";

    /** 内置（classpath）+ 用户 + 项目 三级合并，同名按 项目 > 用户 > 内置 覆盖。 */
    public static Map<String, AgentDef> loadAll(Path userDir, Path projectDir) {
        Map<String, AgentDef> result = new LinkedHashMap<>();
        // 内置（最低优先，classpath）
        loadBuiltin().forEach(d -> result.put(d.name(), d));
        // 用户
        scan(userDir).forEach(d -> result.put(d.name(), d));
        // 项目（最高优先）
        scan(projectDir).forEach(d -> result.put(d.name(), d));
        return result;
    }

    /** 从 classpath 枚举并加载内置 SubAgent。 */
    public static List<AgentDef> loadBuiltin() {
        List<AgentDef> result = new ArrayList<>();
        for (String name : ClasspathScanner.listChildDirs(BUILTIN_RESOURCE)) {
            try {
                String content = ClasspathScanner.readTextOrNull(BUILTIN_RESOURCE + "/" + name + "/agent.md");
                if (content == null) continue;
                AgentDef def = parse(content, BUILTIN_RESOURCE + "/" + name);
                if (def != null) result.add(def);
            } catch (Exception e) {
                System.err.println("[AgentDef] 内置加载失败: " + name + " - " + e.getMessage());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<AgentDef> scan(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        List<AgentDef> list = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path entry : stream.toList()) {
                if (!Files.isDirectory(entry)) continue;
                Path defFile = entry.resolve("agent.md");
                if (!Files.exists(defFile)) continue;
                try {
                    String content = Files.readString(defFile);
                    AgentDef def = parse(content, entry.toString());
                    if (def != null) list.add(def);
                } catch (Exception e) {
                    System.err.println("[AgentDef] 加载失败: " + entry + " - " + e.getMessage());
                }
            }
        } catch (IOException ignored) {}
        return list;
    }

    @SuppressWarnings("unchecked")
    private static AgentDef parse(String content, String source) {
        if (!content.startsWith("---\n")) return null;
        int end = content.indexOf("\n---\n", 4);
        if (end < 0) return null;

        String fmText = content.substring(4, end);
        String body = content.substring(end + 5).trim();

        Yaml yaml = new Yaml();
        Map<String, Object> fm = yaml.load(fmText);
        if (fm == null) return null;

        String name = (String) fm.get("name");
        if (name == null || name.isBlank()) return null;

        String desc = (String) fm.getOrDefault("description", "");
        List<String> tools = (List<String>) fm.getOrDefault("tools", List.of());
        List<String> disallowed = (List<String>) fm.getOrDefault("disallowedTools", List.of());
        String model = (String) fm.get("model");
        int maxRounds = fm.containsKey("maxRounds") ? ((Number) fm.get("maxRounds")).intValue() : 10;
        String permMode = (String) fm.getOrDefault("permissionMode", "bypass");
        String isolation = (String) fm.get("isolation");

        return new AgentDef(name, desc, tools, disallowed, model, maxRounds, permMode, isolation, body, source);
    }
}
