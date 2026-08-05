package com.mewcode.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolRegistry 单元测试。
 */
class ToolRegistryTest {

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
    }

    @Test
    void should_注册成功_When_添加工具() {
        registry.register(new StubTool("read_file", "描述", Map.of()));
        assertTrue(registry.get("read_file").isPresent());
        assertEquals(1, registry.size());
    }

    @Test
    void should_返回空_When_查找不存在的工具() {
        assertTrue(registry.get("nonexistent").isEmpty());
    }

    @Test
    void should_输出正确格式_When_调用toToolDefinitions() {
        registry.register(new StubTool("read_file", "读文件", Map.of("type", "object")));
        registry.register(new StubTool("grep", "搜索", Map.of("type", "object")));

        List<Map<String, Object>> defs = registry.toToolDefinitions();
        assertEquals(2, defs.size());
        assertEquals("read_file", defs.get(0).get("name"));
        assertEquals("读文件", defs.get(0).get("description"));
        assertEquals(Map.of("type", "object"), defs.get(0).get("input_schema"));
        assertEquals("grep", defs.get(1).get("name"));
    }

    @Test
    void should_遍历所有名称_When_六个工具注册后() {
        for (String name : List.of("read_file", "write_file", "edit_file",
                "exec_command", "glob", "grep")) {
            registry.register(new StubTool(name, "desc", Map.of()));
        }
        assertEquals(6, registry.size());
        assertTrue(registry.names().containsAll(
                List.of("read_file", "write_file", "edit_file", "exec_command", "glob", "grep")));
    }

    /** 测试用的桩工具 */
    private record StubTool(String name, String description,
                            Map<String, Object> inputSchema) implements Tool {
        @Override
        public ToolResult execute(Map<String, Object> params) {
            return ToolResult.ok("ok");
        }
    }
}
