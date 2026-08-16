package com.rook.tools.impl;

import com.rook.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EditFileTool 单元测试。
 */
class EditFileToolTest {

    private final EditFileTool tool = new EditFileTool();

    @Test
    void should_替换成功_When_唯一匹配(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("test.txt");
        Files.writeString(file, "Hello World");

        ToolResult result = tool.execute(Map.of(
                "filePath", file.toString(),
                "oldString", "World",
                "newString", "Java"));

        assertTrue(result.success());
        assertEquals("Hello Java", Files.readString(file));
    }

    @Test
    void should_报未找到_When_0次匹配(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("test.txt");
        Files.writeString(file, "Hello World");

        ToolResult result = tool.execute(Map.of(
                "filePath", file.toString(),
                "oldString", "XYZ",
                "newString", "ABC"));

        assertFalse(result.success());
        assertTrue(result.content().contains("未找到"));
    }

    @Test
    void should_报多次匹配_When_匹配超过1次(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("test.txt");
        Files.writeString(file, "foo bar foo");

        ToolResult result = tool.execute(Map.of(
                "filePath", file.toString(),
                "oldString", "foo",
                "newString", "baz"));

        assertFalse(result.success());
        assertTrue(result.content().contains("2 处"));
    }
}
