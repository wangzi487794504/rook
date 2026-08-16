package com.rook.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rook.llm.ChatMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 对话会话管理，负责消息历史的存储和持久化。
 *
 * @author wangzijie
 * @since 0.1.0
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class Session {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final List<ChatMessage> messages;

    /**
     * 创建空会话。
     */
    /** @return 方法返回值 */
    public Session() {
        this.messages = new ArrayList<>();
    }

    private Session(List<ChatMessage> messages) {
        this.messages = new ArrayList<>(messages);
    }

    /**
     * 返回消息历史的不可变视图。
     */
    /** @return 方法返回值 */
    public List<ChatMessage> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    /**
     * 添加一条消息到历史记录中。
     */
    public void addMessage(ChatMessage msg) {
        messages.add(msg);
    }

    /**
     * 清空所有消息历史。
     */
    public void clear() {
        messages.clear();
    }

    /**
     * 将当前消息历史保存到 JSON 文件。
     */
    public void save(Path path) throws IOException {
        // 确保父目录存在
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), messages);
    }

    /**
     * 从 JSON 文件恢复会话。文件不存在时返回空会话。
     */
    public static Session load(Path path) throws IOException {
        if (!Files.exists(path)) {
            return new Session();
        }
        List<ChatMessage> msgs = MAPPER.readValue(
                path.toFile(),
                MAPPER.getTypeFactory().constructCollectionType(List.class, ChatMessage.class));
        return new Session(msgs);
    }
}
