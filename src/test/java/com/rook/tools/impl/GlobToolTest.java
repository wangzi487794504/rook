package com.rook.tools.impl;

import com.rook.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GlobTool 单元测试。
 */
class GlobToolTest {

    private final GlobTool tool = new GlobTool();

    @Test
    void should_返回匹配文件_When_存在匹配(@TempDir Path tmp) throws Exception {
        Files.createFile(tmp.resolve("Test.java"));
        Files.createFile(tmp.resolve("Test.txt"));
        Files.createFile(tmp.resolve("README.md"));

        ToolResult result = tool.execute(Map.of("pattern", "*.java", "path", tmp.toString()));
        assertTrue(result.success());
        assertTrue(result.content().contains("Test.java"));
        assertFalse(result.content().contains("Test.txt"));
    }

    @Test
    void should_返回未找到_When_无匹配(@TempDir Path tmp) {
        ToolResult result = tool.execute(Map.of("pattern", "*.xyz", "path", tmp.toString()));
        assertTrue(result.success());
        assertTrue(result.content().contains("未找到"));
    }
}
