package com.mewcode.subagent;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 角色定义加载器 —— 四级来源(项目>用户>内置>插件)，同名覆盖。
 */
public class AgentDefLoader {

    @SuppressWarnings("unchecked")
    public static Map<String, AgentDef> loadAll(Path builtinDir, Path userDir, Path projectDir) {
        Map<String, AgentDef> result = new LinkedHashMap<>();
        scan(builtinDir).forEach(d -> result.put(d.name(), d));
        scan(userDir).forEach(d -> result.put(d.name(), d));
        scan(projectDir).forEach(d -> result.put(d.name(), d));
        // 插件级(恒为空，占位)
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
