package com.mewcode.session;

import com.mewcode.llm.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Session 单元测试。
 *
 * @author MewCode
 * @since 0.1.0
 */
class SessionTest {

    @Test
    void should_返回空消息列表_When_新创建的Session() {
        Session session = new Session();
        assertTrue(session.getMessages().isEmpty());
    }

    @Test
    void should_正确返回消息_When_添加消息后() {
        Session session = new Session();
        session.addMessage(new ChatMessage("user", "你好"));
        session.addMessage(new ChatMessage("assistant", "你好！有什么可以帮助你的？"));

        List<ChatMessage> messages = session.getMessages();
        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).role());
        assertEquals("你好", messages.get(0).content());
        assertEquals("assistant", messages.get(1).role());
    }

    @Test
    void should_往返结果一致_When_save和load(@TempDir Path tempDir) throws IOException {
        Session session = new Session();
        session.addMessage(new ChatMessage("user", "测试消息"));
        session.addMessage(new ChatMessage("assistant", "回复消息"));

        Path savePath = tempDir.resolve("test-session.json");
        session.save(savePath);

        Session loaded = Session.load(savePath);
        List<ChatMessage> loadedMessages = loaded.getMessages();

        assertEquals(2, loadedMessages.size());
        assertEquals("user", loadedMessages.get(0).role());
        assertEquals("测试消息", loadedMessages.get(0).content());
        assertEquals("assistant", loadedMessages.get(1).role());
        assertEquals("回复消息", loadedMessages.get(1).content());
    }

    @Test
    void should_返回空Session_When_加载不存在的文件(@TempDir Path tempDir) throws IOException {
        Path nonExistent = tempDir.resolve("nonexistent.json");
        Session session = Session.load(nonExistent);
        assertTrue(session.getMessages().isEmpty());
    }

    @Test
    void should_消息顺序正确_When_多轮对话() {
        Session session = new Session();
        session.addMessage(new ChatMessage("user", "第1条"));
        session.addMessage(new ChatMessage("assistant", "第2条"));
        session.addMessage(new ChatMessage("user", "第3条"));

        List<ChatMessage> messages = session.getMessages();
        assertEquals(3, messages.size());
        assertEquals("第1条", messages.get(0).content());
        assertEquals("第2条", messages.get(1).content());
        assertEquals("第3条", messages.get(2).content());
    }

    @Test
    void should_消息被清空_When_调用clear() {
        Session session = new Session();
        session.addMessage(new ChatMessage("user", "你好"));
        session.clear();
        assertTrue(session.getMessages().isEmpty());
    }
}
