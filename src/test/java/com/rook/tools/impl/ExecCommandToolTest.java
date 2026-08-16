package com.rook.tools.impl;

import com.rook.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExecCommandTool 单元测试。
 */
class ExecCommandToolTest {

    private final ExecCommandTool tool = new ExecCommandTool();

    @Test
    void should_返回输出_When_执行echo() {
        String cmd = System.getProperty("os.name").toLowerCase().contains("win")
                ? "echo hello" : "echo hello";

        ToolResult result = tool.execute(Map.of("command", cmd));
        assertTrue(result.success());
        assertTrue(result.content().contains("hello"));
    }

    @Test
    void should_返回错误_When_缺少参数() {
        ToolResult result = tool.execute(Map.of());
        assertFalse(result.success());
    }
}
