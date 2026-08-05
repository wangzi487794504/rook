package com.mewcode.tools;

import java.util.*;

/**
 * 工具注册中心 —— 集中登记所有可用工具，提供查找和 API 格式输出。
 *
 * @author MewCode
 * @since 0.2.0
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /**
     * 注册一个工具。同名工具后注册的会覆盖先注册的。
     */
    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    /**
     * 按名称查找工具。
     *
     * @return Optional，未找到时为空
     */
    public Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * 返回所有注册的工具名称集合。
     */
    public Set<String> names() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    /**
     * 将注册的所有工具输出为 Anthropic API 所需的工具定义列表。
     * 格式：
     * [
     *   { "name": "read_file", "description": "...", "input_schema": {...} },
     *   ...
     * ]
     */
    public List<Map<String, Object>> toToolDefinitions() {
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (Tool tool : tools.values()) {
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("name", tool.name());
            def.put("description", tool.description());
            def.put("input_schema", tool.inputSchema());
            definitions.add(def);
        }
        return definitions;
    }

    /** 移除指定名称的工具 */
    public void unregister(String name) {
        tools.remove(name);
    }

    public int size() {
        return tools.size();
    }
}
