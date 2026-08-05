package com.mewcode.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Skill 加载器 —— 三级目录扫描 + frontmatter 解析 + 优先级合并。
 */
public class SkillLoader {

    private static final Pattern NAME_RE = Pattern.compile("^[a-z][a-z0-9-]*$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 三级扫描并合并，同名按 项目 > 用户 > 内置 覆盖 */
    @SuppressWarnings("unchecked")
    public static Map<String, SkillDef> loadAll(Path builtinDir, Path userDir, Path projectDir) {
        Map<String, SkillDef> result = new LinkedHashMap<>();
        // 内置（最低优先）
        scan(builtinDir).forEach(s -> result.put(s.name(), s));
        // 用户
        scan(userDir).forEach(s -> result.put(s.name(), s));
        // 项目（最高优先）
        scan(projectDir).forEach(s -> result.put(s.name(), s));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<SkillDef> scan(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        List<SkillDef> list = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path entry : stream.toList()) {
                if (!Files.isDirectory(entry)) continue;
                try {
                    SkillDef skill = loadSkill(entry);
                    if (skill != null) list.add(skill);
                } catch (Exception e) {
                    System.err.println("[Skill] 加载失败: " + entry + " - " + e.getMessage());
                }
            }
        } catch (IOException ignored) {}
        return list;
    }

    @SuppressWarnings("unchecked")
    private static SkillDef loadSkill(Path dir) throws IOException {
        Path skillMd = dir.resolve("SKILL.md");
        if (!Files.exists(skillMd)) return null;

        String content = Files.readString(skillMd);
        if (!content.startsWith("---\n")) return null;

        int end = content.indexOf("\n---\n", 4);
        if (end < 0) return null;

        String fmText = content.substring(4, end);
        String body = content.substring(end + 5).trim();

        Yaml yaml = new Yaml();
        Map<String, Object> fm = yaml.load(fmText);
        if (fm == null) return null;

        String name = (String) fm.get("name");
        if (name == null || name.length() < 1 || name.length() > 32 || !NAME_RE.matcher(name).matches()) {
            System.err.println("[Skill] name 不合法: " + name);
            return null;
        }

        String desc = (String) fm.getOrDefault("description", "");
        List<String> tools = (List<String>) fm.getOrDefault("allowed_tools", List.of());
        String mode = (String) fm.getOrDefault("mode", "inline");
        if (!"inline".equals(mode) && !"fork".equals(mode)) {
            System.err.println("[Skill:" + name + "] 未知 mode: " + mode + ", 降级为 inline");
            mode = "inline";
        }
        String forkCtx = (String) fm.getOrDefault("fork_context", "none");
        String model = (String) fm.get("model");

        // tool.json
        Map<String, SkillToolDef> skillTools = Map.of();
        Path toolJson = dir.resolve("tool.json");
        if (Files.exists(toolJson)) {
            List<Map<String, Object>> toolList = MAPPER.readValue(toolJson.toFile(), List.class);
            Map<String, SkillToolDef> tm = new LinkedHashMap<>();
            for (var t : toolList) {
                String tName = (String) t.get("name");
                String tDesc = (String) t.getOrDefault("description", "");
                Map<String, Object> schema = (Map<String, Object>) t.getOrDefault("input_schema", Map.of());
                List<String> cmd = (List<String>) t.getOrDefault("command", List.of());
                tm.put(tName, new SkillToolDef(tName, tDesc, schema, cmd));
            }
            skillTools = tm;
        }

        return new SkillDef(name, desc, tools, mode, forkCtx, model, body, skillTools, dir.toString());
    }
}
