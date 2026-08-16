package com.rook.team;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** G7-G10: 点对点消息邮箱 + 锁文件并发安全 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class Mailbox {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RETRIES = 10;

    private final Path mailDir;

    /** @return 方法返回值 */
    public Mailbox(Path teamDir) { this.mailDir = teamDir.resolve("mailboxes"); }

    /** 发送消息 G7 */
    public void send(String from, String to, String text, String type, String summary) throws IOException {
        Files.createDirectories(mailDir);
        Message msg = new Message(from, to, text, Instant.now().toEpochMilli(), false, summary, type);
        Path mailbox = mailDir.resolve(to + ".jsonl");

        // G8: 锁文件并发安全
        Path lockFile = mailDir.resolve(to + ".lock");
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                // 创建锁文件
                try { Files.createFile(lockFile); }
                catch (FileAlreadyExistsException e) {
                    // 检查是否 stale (>10s)
                    if (Files.getLastModifiedTime(lockFile).toMillis()
                            < System.currentTimeMillis() - 10000) {
                        Files.delete(lockFile); // stale 过期清掉
                    } else {
                        Thread.sleep(5 + ThreadLocalRandom.current().nextInt(95)); // 随机抖动
                        continue;
                    }
                }
                // 持锁成功 → 追加消息
                Files.writeString(mailbox, MAPPER.writeValueAsString(msg) + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                Files.delete(lockFile);
                return;
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        throw new IOException("邮箱锁竞争失败: " + to);
    }

    /** 读取未读消息 G10 */
    /** @return 方法返回值 */
    public List<Message> readUnread(String recipient) throws IOException {
        Path mailbox = mailDir.resolve(recipient + ".jsonl");
        if (!Files.exists(mailbox)) return List.of();

        List<Message> all = new ArrayList<>();
        List<Message> unread = new ArrayList<>();
        List<String> lines = Files.readAllLines(mailbox);

        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                Message m = MAPPER.readValue(line, Message.class);
                all.add(m);
                if (!m.read) unread.add(m);
            } catch (Exception ignored) {}
        }

        // 批量标记为 read
        for (Message m : all) m.read = true;
        List<String> updated = all.stream().map(m -> {
            try { return MAPPER.writeValueAsString(m); } catch (Exception e) { return ""; }
        }).filter(s -> !s.isBlank()).toList();
        Files.write(mailbox, updated);

        return unread;
    }

    /** 队员邮箱文件列表 */
    /** @return 方法返回值 */
    public List<Path> listMailboxes() throws IOException {
        if (!Files.exists(mailDir)) return List.of();
        try (var s = Files.list(mailDir)) {
            return s.filter(p -> p.toString().endsWith(".jsonl")).toList();
        }
    }

    /** 消息记录 */
    public static class Message {
        public String from, to, text, type, summary;
        public long timestamp;
        public boolean read;
        public Message() {} // Jackson
        public Message(String from, String to, String text, long ts, boolean read, String summary, String type) {
            this.from = from; this.to = to; this.text = text; this.timestamp = ts;
            this.read = read; this.summary = summary; this.type = type != null ? type : "text";
        }
    }
}
