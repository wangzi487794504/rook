package com.rook.llm;

import com.rook.tools.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StreamCollector 单元测试。
 */
class StreamCollectorTest {

    @Test
    void should_同时推送和累积_When_接收token() {
        List<String> realtime = new ArrayList<>();
        StreamCollector collector = new StreamCollector(realtime::add);

        collector.onToken("Hello");
        collector.onToken(" ");
        collector.onToken("World");

        // 实时通道
        assertEquals(List.of("Hello", " ", "World"), realtime);
        // 累积通道
        assertEquals("Hello World", collector.getFullText());
    }

    @Test
    void should_累积工具调用_When_接收onToolCall() {
        StreamCollector collector = new StreamCollector(t -> {});
        collector.onToolCall(new ToolCall("tc1", "read_file", Map.of("filePath", "test.txt")));
        collector.onToolCall(new ToolCall("tc2", "grep", Map.of("pattern", "hello")));

        assertEquals(2, collector.getToolCalls().size());
        assertEquals("read_file", collector.getToolCalls().get(0).name());
        assertEquals("grep", collector.getToolCalls().get(1).name());
    }

    @Test
    void should_记录用量_When_接收onUsage() {
        StreamCollector collector = new StreamCollector(t -> {});
        collector.onUsage(new Usage(100, 50));

        assertEquals(100, collector.getUsage().inputTokens());
        assertEquals(50, collector.getUsage().outputTokens());
    }

    @Test
    void should_空collector返回空_When_未接收任何数据() {
        StreamCollector collector = new StreamCollector(t -> {});
        assertEquals("", collector.getFullText());
        assertTrue(collector.getToolCalls().isEmpty());
        assertEquals(Usage.ZERO, collector.getUsage());
    }
}
