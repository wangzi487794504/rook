package com.rook.prompt;

import com.rook.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SupplementMessageTest {

    @Test
    void should_带标签_When_create() {
        ChatMessage msg = SupplementMessage.create("测试指令");
        assertEquals("user", msg.role());
        assertTrue(msg.content().startsWith("[Supplement] "));
        assertTrue(msg.content().contains("测试指令"));
    }

    @Test
    void should_识别补充消息_When_isSupplement() {
        ChatMessage msg = SupplementMessage.create("test");
        assertTrue(SupplementMessage.isSupplement(msg));
    }

    @Test
    void should_不识别普通消息_When_不含标签() {
        ChatMessage msg = new ChatMessage("user", "普通消息");
        assertFalse(SupplementMessage.isSupplement(msg));
    }
}
