package com.mewcode.hook;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Hook 规则：name + event + if + action + only_once + async + timeout。
 */
public record HookRule(
        String name,
        HookEvent event,
        HookCondition condition,
        HookAction action,
        boolean onlyOnce,
        boolean async,
        int timeoutSec
) {
    @SuppressWarnings("unchecked")
    public static HookRule fromMap(Map<String, Object> map, String source) {
        String name = (String) map.get("name");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("缺少 name 字段");

        String eventStr = (String) map.get("event");
        if (eventStr == null) throw new IllegalArgumentException(name + ": 缺少 event");
        HookEvent event = HookEvent.valueOf(eventStr.toUpperCase());

        Map<String, Object> condMap = (Map<String, Object>) map.get("if");
        HookCondition cond = condMap != null ? parseCondition(condMap) : null;

        Map<String, Object> actMap = (Map<String, Object>) map.get("action");
        if (actMap == null) throw new IllegalArgumentException(name + ": 缺少 action");
        HookAction action = parseAction(actMap);

        boolean once = Boolean.TRUE.equals(map.get("only_once"));
        boolean async = Boolean.TRUE.equals(map.get("async"));
        int timeout = parseTimeout((String) map.getOrDefault("timeout", "30s"));

        // G8: 拦截类事件不允许 async
        if (async && isInterceptEvent(event)) {
            throw new IllegalArgumentException(
                    name + ": 拦截类事件(" + event + ")不允许 async");
        }

        return new HookRule(name, event, cond, action, once, async, timeout);
    }

    private static boolean isInterceptEvent(HookEvent e) {
        return e == HookEvent.PRE_TOOL_USE || e == HookEvent.USER_PROMPT_SUBMIT;
    }

    private static int parseTimeout(String s) {
        if (s == null) return 30;
        s = s.toLowerCase().trim();
        if (s.endsWith("s")) s = s.substring(0, s.length() - 1);
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 30; }
    }

    @SuppressWarnings("unchecked")
    private static HookCondition parseCondition(Map<String, Object> map) {
        if (map.containsKey("all_of")) {
            var subs = ((List<Map<String, Object>>) map.get("all_of"))
                    .stream().map(HookRule::parseMatcher).toList();
            return new HookCondition.AllOf(subs);
        }
        if (map.containsKey("any_of")) {
            var subs = ((List<Map<String, Object>>) map.get("any_of"))
                    .stream().map(HookRule::parseMatcher).toList();
            return new HookCondition.AnyOf(subs);
        }
        return new HookCondition.AllOf(List.of(parseMatcher(map)));
    }

    private static HookMatcher parseMatcher(Map<String, Object> m) {
        String field = (String) m.get("field");
        Object exact = m.get("exact");
        Object not = m.get("not");
        Object regex = m.get("regex");
        Object glob = m.get("glob");
        if (not != null) return new HookMatcher(field, "not", not.toString(), null);
        if (regex != null) {
            Pattern.compile(regex.toString()); // 预编译校验
            return new HookMatcher(field, "regex", regex.toString(), null);
        }
        if (glob != null) return new HookMatcher(field, "glob", glob.toString(), null);
        if (exact != null) return new HookMatcher(field, "exact", exact.toString(), null);
        return new HookMatcher(field, "exact", "", null);
    }

    @SuppressWarnings("unchecked")
    private static HookAction parseAction(Map<String, Object> map) {
        String type = (String) map.get("type");
        return switch (type) {
            case "command" -> new HookAction.Command((String) map.get("command"));
            case "prompt" -> new HookAction.Prompt(
                    (String) map.get("text"), (String) map.getOrDefault("position", "before"));
            case "http" -> new HookAction.HttpRequest(
                    (String) map.get("url"), (String) map.getOrDefault("method", "POST"),
                    (Map<String,String>) map.getOrDefault("headers", Map.of()),
                    (String) map.getOrDefault("body", ""));
            case "subagent" -> new HookAction.SubAgent(
                    (String) map.getOrDefault("name", "default"), (String) map.get("prompt"));
            default -> throw new IllegalArgumentException("未知动作类型: " + type);
        };
    }
}
