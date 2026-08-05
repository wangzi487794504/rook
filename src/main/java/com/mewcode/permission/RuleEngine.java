package com.mewcode.permission;

import com.mewcode.tools.ToolCall;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.*;

/**
 * 第3层：规则引擎 —— 三层 YAML 规则合并，越本地越优先。
 *
 * @author MewCode
 * @since 0.5.0
 */
public class RuleEngine {

    private final List<RuleConfig> mergedRules; // 已按优先级排好

    public RuleEngine(Path userLevel, Path projectLevel, Path localLevel) {
        List<RuleConfig> rules = new ArrayList<>();
        // 加载顺序：低优先级先加，高优先级后加（覆盖）
        rules.addAll(load(userLevel, "user"));
        rules.addAll(load(projectLevel, "project"));
        rules.addAll(load(localLevel, "local"));
        this.mergedRules = List.copyOf(rules);
    }

    /** 五层调度中：遍历规则，命中则短路返回 */
    public PermissionResult check(ToolCall call) {
        for (RuleConfig rule : mergedRules) {
            if (!call.name().equals(rule.toolName())) continue;
            if (rule.pattern() == null) {
                // 匹配所有参数
                return rule.allow()
                        ? PermissionResult.allow()
                        : PermissionResult.deny("规则拒绝: " + rule.toolName(), "rule");
            }
            // ★ 匹配参数（支持 exact/not/regex/glob + 向后兼容 glob 字符串）
            String param = extractParam(call);
            if (param != null && matchRule(rule.pattern(), param)) {
                return rule.allow()
                        ? PermissionResult.allow()
                        : PermissionResult.deny(
                                "规则拒绝: " + rule.toolName() + "(" + param + ")", "rule");
            }
        }
        return PermissionResult.allow(); // 未命中 → 穿透
    }

    private String extractParam(ToolCall call) {
        // 返回第一个字符串参数值作为匹配目标
        for (Object v : call.input().values()) {
            if (v instanceof String s && !s.isBlank()) return s;
        }
        return null;
    }

    /** 匹配 —— 支持字符串(glob)或结构化 {type, value} */
    @SuppressWarnings("unchecked")
    private boolean matchRule(String pattern, String target) {
        if (pattern == null || target == null) return false;
        if (pattern.isEmpty()) return true; // 空=匹配所有

        // 结构化匹配: {type: exact|not|regex|glob, value: "..."}
        if (pattern.startsWith("{")) {
            try {
                Map<String, Object> m = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(pattern, Map.class);
                String type = (String) m.getOrDefault("type", "glob");
                String value = (String) m.get("value");
                if (value == null) return false;
                com.mewcode.hook.HookMatcher hm = new com.mewcode.hook.HookMatcher(
                        "", type, value, null);
                return hm.matches(target);
            } catch (Exception e) {
                return target.equals(pattern);
            }
        }

        // 向后兼容：简单字符串 = glob
        try {
            return FileSystems.getDefault().getPathMatcher("glob:" + pattern)
                    .matches(Path.of(target));
        } catch (Exception e) {
            return target.equals(pattern);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<RuleConfig> load(Path file, String source) {
        if (file == null || !Files.exists(file)) return List.of();
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(Files.newInputStream(file));
            if (root == null) return List.of();
            List<Map<String, Object>> ruleList = (List<Map<String, Object>>) root.get("rules");
            if (ruleList == null) return List.of();

            return ruleList.stream()
                    .map(m -> new RuleConfig(
                            String.valueOf(m.get("tool")),
                            m.containsKey("pattern") && m.get("pattern") != null
                                    ? String.valueOf(m.get("pattern")) : null,
                            "allow".equals(String.valueOf(m.get("action"))),
                            source))
                    .toList();
        } catch (IOException e) {
            return List.of(); // 加载失败不崩溃
        }
    }
}
