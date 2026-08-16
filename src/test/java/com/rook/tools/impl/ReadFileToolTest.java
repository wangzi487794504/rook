package com.rook.tools.impl;

import com.rook.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReadFileTool 单元测试。
 */
class ReadFileToolTest {

    private final ReadFileTool tool = new ReadFileTool();

    @Test
    void should_返回文件内容_When_文件存在(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("test.txt");
        Files.writeString(file, "Hello\nWorld");

        ToolResult result = tool.execute(Map.of("filePath", file.toString()));
        assertTrue(result.success());
        assertTrue(result.content().contains("Hello"));
        assertTrue(result.content().contains("World"));
    }

    @Test
    void should_返回错误_When_文件不存在() {
        ToolResult result = tool.execute(Map.of("filePath", "/nonexistent/file.txt"));
        assertFalse(result.success());
        assertTrue(result.content().contains("不存在"));
    }

    @Test
    void should_返回错误_When_缺少参数() {
        ToolResult result = tool.execute(Map.of());
        assertFalse(result.success());
    }
}
