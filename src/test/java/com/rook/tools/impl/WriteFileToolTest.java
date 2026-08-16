package com.rook.tools.impl;

import com.rook.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WriteFileTool 单元测试。
 */
class WriteFileToolTest {

    private final WriteFileTool tool = new WriteFileTool();

    @Test
    void should_写入成功_When_正常写入(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("output.txt");
        ToolResult result = tool.execute(Map.of(
                "filePath", file.toString(), "content", "测试内容"));

        assertTrue(result.success());
        assertTrue(Files.exists(file));
        assertEquals("测试内容", Files.readString(file));
    }

    @Test
    void should_覆盖已有文件_When_文件已存在(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("output.txt");
        Files.writeString(file, "旧内容");

        tool.execute(Map.of("filePath", file.toString(), "content", "新内容"));
        assertEquals("新内容", Files.readString(file));
    }

    @Test
    void should_自动创建父目录_When_父目录不存在(@TempDir Path tmp) {
        Path file = tmp.resolve("deep/nested/dir/test.txt");
        ToolResult result = tool.execute(Map.of(
                "filePath", file.toString(), "content", "hello"));
        assertTrue(result.success());
    }
}
