package com.rook.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rook.util.ClasspathScanner;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Skill 加载器 —— 内置从 classpath 加载，用户/项目从文件系统加载，优先级合并。
 *
 * @author wangzijie
 * @since 0.1.0
 */
public class SkillLoader {

    /** 内置 Skill 在 classpath 中的根路径（打包后位于 fat jar 内） */
    private static final String BUILTIN_RESOURCE = "com/rook/skills/builtin";
    private static final Pattern NAME_RE = Pattern.compile("^[a-z][a-z0-9-]*$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 内置（classpath）+ 用户 + 项目 三级合并，同名按 项目 > 用户 > 内置 覆盖 */
    public static Map<String, SkillDef> loadAll(Path userDir, Path projectDir) {
        Map<String, SkillDef> result = new LinkedHashMap<>();
        // 内置（最低优先，classpath）
        loadBuiltin().forEach(s -> result.put(s.name(), s));
        // 用户
        scan(userDir).forEach(s -> result.put(s.name(), s));
        // 项目（最高优先）
        scan(projectDir).forEach(s -> result.put(s.name(), s));
        return result;
    }

    /** 从 classpath 枚举并加载内置 Skill。 */
    public static List<SkillDef> loadBuiltin() {
        List<SkillDef> result = new ArrayList<>();
        for (String name : ClasspathScanner.listChildDirs(BUILTIN_RESOURCE)) {
            try {
                String content = ClasspathScanner.readTextOrNull(BUILTIN_RESOURCE + "/" + name + "/SKILL.md");
                if (content == null) continue;
                String toolJson = ClasspathScanner.readTextOrNull(BUILTIN_RESOURCE + "/" + name + "/tool.json");
                SkillDef def = parseSkill(BUILTIN_RESOURCE + "/" + name, content, toolJson);
                if (def != null) result.add(def);
            } catch (Exception e) {
                System.err.println("[Skill] 内置加载失败: " + name + " - " + e.getMessage());
            }
        }
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

    /** 从文件系统目录读取并解析单个 Skill。 */
    private static SkillDef loadSkill(Path dir) throws IOException {
        Path skillMd = dir.resolve("SKILL.md");
        if (!Files.exists(skillMd)) return null;
        String content = Files.readString(skillMd);
        String toolJson = null;
        Path toolJsonPath = dir.resolve("tool.json");
        if (Files.exists(toolJsonPath)) toolJson = Files.readString(toolJsonPath);
        return parseSkill(dir.toString(), content, toolJson);
    }

    /** 解析 SKILL.md 内容（文件系统与 classpath 共用）。 */
    @SuppressWarnings("unchecked")
    private static SkillDef parseSkill(String source, String content, String toolJson) {
        if (content == null || !content.startsWith("---\n")) return null;

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

        Map<String, SkillToolDef> skillTools = parseToolJson(toolJson);

        return new SkillDef(name, desc, tools, mode, forkCtx, model, body, skillTools, source);
    }

    /** 解析 tool.json（可选）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, SkillToolDef> parseToolJson(String toolJson) {
        if (toolJson == null || toolJson.isBlank()) return Map.of();
        try {
            List<Map<String, Object>> toolList = MAPPER.readValue(toolJson, List.class);
            Map<String, SkillToolDef> tm = new LinkedHashMap<>();
            for (var t : toolList) {
                String tName = (String) t.get("name");
                String tDesc = (String) t.getOrDefault("description", "");
                Map<String, Object> schema = (Map<String, Object>) t.getOrDefault("input_schema", Map.of());
                List<String> cmd = (List<String>) t.getOrDefault("command", List.of());
                tm.put(tName, new SkillToolDef(tName, tDesc, schema, cmd));
            }
            return tm;
        } catch (IOException e) {
            System.err.println("[Skill] tool.json 解析失败: " + e.getMessage());
            return Map.of();
        }
    }
}
