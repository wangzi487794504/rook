package com.mewcode.tools.impl;

import com.mewcode.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GrepTool 单元测试。
 */
class GrepToolTest {

    private final GrepTool tool = new GrepTool();

    @Test
    void should_找到匹配内容_When_搜索存在文本(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("test.txt");
        Files.writeString(file, "Hello World\nAnother line\nHello again");

        ToolResult result = tool.execute(Map.of("pattern", "Hello", "path", tmp.toString()));
        assertTrue(result.success());
        assertTrue(result.content().contains("Hello"));
        // grep 应返回"文件:行号:内容"格式
        assertTrue(result.content().contains("test.txt:"));
    }

    @Test
    void should_返回未找到_When_无匹配(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("test.txt"), "No match here");

        ToolResult result = tool.execute(Map.of("pattern", "ZZZZZ", "path", tmp.toString()));
        assertTrue(result.success());
        assertTrue(result.content().contains("未找到"));
    }
}
