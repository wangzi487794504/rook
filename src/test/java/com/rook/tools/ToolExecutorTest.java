package com.rook.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.rook.permission.PermissionEngine;
import com.rook.permission.PathSandbox;
import com.rook.permission.RuleEngine;
import com.rook.permission.HumanInTheLoop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolExecutor 单元测试。
 */
class ToolExecutorTest {

    private ToolRegistry registry;
    private ToolExecutor executor;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        registry.register(new StubReadTool("read_file"));
        registry.register(new StubReadTool("glob"));
        registry.register(new StubReadTool("grep"));
        registry.register(new StubWriteTool("write_file"));
        registry.register(new StubWriteTool("exec_command"));
        Path tmp = Paths.get(System.getProperty("java.io.tmpdir"));
        var sandbox = new PathSandbox(tmp);
        var ruleEngine = new RuleEngine(null, null, null);
        var human = new HumanInTheLoop(null);
        var permEngine = new PermissionEngine(sandbox, ruleEngine, human);
        permEngine.setMode(com.rook.permission.PermissionMode.BYPASS);
        executor = new ToolExecutor(registry, 3, permEngine, null);
    }

    @Test
    void should_并发执行_When_全是只读工具() {
        List<ToolCall> calls = List.of(
                new ToolCall("t1", "read_file", Map.of()),
                new ToolCall("t2", "glob", Map.of()),
                new ToolCall("t3", "grep", Map.of())
        );

        long start = System.currentTimeMillis();
        List<ToolResult> results = executor.execute(calls);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(3, results.size());
        assertTrue(results.stream().allMatch(ToolResult::success));
        // 并发执行应比 3×100ms 串行快得多
        assertTrue(elapsed < 600, "并发执行应 < 600ms，实际: " + elapsed + "ms");
    }

    @Test
    void should_保序_When_读写混搭() {
        List<ToolCall> calls = List.of(
                new ToolCall("t1", "read_file", Map.of()),
                new ToolCall("t2", "write_file", Map.of()),
                new ToolCall("t3", "glob", Map.of())
        );

        List<ToolResult> results = executor.execute(calls);
        assertEquals(3, results.size());
        assertTrue(results.get(0).success()); // read
        assertTrue(results.get(1).success()); // write
        assertTrue(results.get(2).success()); // glob
    }

    @Test
    void should_处理空列表_When_无工具调用() {
        List<ToolResult> results = executor.execute(List.of());
        assertTrue(results.isEmpty());
    }

    /** 100ms 延迟的只读桩 */
    record StubReadTool(String name) implements Tool {
        @Override public String description() { return "stub"; }
        @Override public Map<String, Object> inputSchema() { return Map.of(); }
        @Override
        public ToolResult execute(Map<String, Object> params) {
            try { Thread.sleep(100); } catch (InterruptedException e) {}
            return ToolResult.ok("ok");
        }
    }

    /** 10ms 延迟的写入桩 */
    record StubWriteTool(String name) implements Tool {
        @Override public String description() { return "stub"; }
        @Override public Map<String, Object> inputSchema() { return Map.of(); }
        @Override
        public ToolResult execute(Map<String, Object> params) {
            try { Thread.sleep(10); } catch (InterruptedException e) {}
            return ToolResult.ok("ok");
        }
    }
}
