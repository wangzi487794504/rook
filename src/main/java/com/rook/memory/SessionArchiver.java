package com.rook.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rook.llm.ChatMessage;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 会话存档 —— JSONL 追加/恢复/清理。
 *
 * @author wangzijie
 * @since 0.8.0
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class SessionArchiver {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 生成会话 ID */
    public static String newSessionId() {
        String ts = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String rand = String.format("%04d", new Random().nextInt(10000));
        return ts + "-" + rand;
    }

    /** 追加一条消息到 JSONL */
    public static void append(Path file, ChatMessage msg) {
        try {
            Files.createDirectories(file.getParent());
            String line = MAPPER.writeValueAsString(msg) + "\n";
            Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[Session] 写入失败: " + e.getMessage());
        }
    }

    /** 从 JSONL 恢复消息列表 */
    public static List<ChatMessage> load(Path file) {
        List<ChatMessage> messages = new ArrayList<>();
        if (!Files.exists(file)) return messages;

        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (line.isBlank()) continue;
                try {
                    ChatMessage msg = MAPPER.readValue(line, ChatMessage.class);
                    messages.add(msg);
                } catch (Exception e) {
                    System.err.println("[Session] 坏行跳过: " + file + ":" + lineNum);
                }
            }
        } catch (IOException e) {
            System.err.println("[Session] 读取失败: " + e.getMessage());
        }

        // 工具调用配对截断：如果最后一条是 assistant+tool_use 但无 tool_result，移除
        if (!messages.isEmpty()) {
            ChatMessage last = messages.get(messages.size() - 1);
            if (last.toolCalls() != null && !last.toolCalls().isEmpty()) {
                messages.remove(messages.size() - 1);
            }
        }

        // 时间跨度提醒
        List<ChatMessage> result = new ArrayList<>();
        Instant lastTime = null;
        for (ChatMessage m : messages) {
            if (lastTime != null) {
                // 用当前时间作为基准（简化处理）
            }
            result.add(m);
        }
        return result;
    }

    /** 列出现有会话 */
    public static List<Path> list(Path sessionsDir) throws IOException {
        if (!Files.exists(sessionsDir)) return List.of();
        try (var stream = Files.list(sessionsDir)) {
            return stream.filter(p -> p.toString().endsWith(".jsonl"))
                    .sorted(Comparator.reverseOrder())
                    .toList();
        }
    }

    /** 清理超过 days 天的会话 */
    public static int cleanOld(Path sessionsDir, int days) {
        if (!Files.exists(sessionsDir)) return 0;
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        int count = 0;
        try (var stream = Files.list(sessionsDir)) {
            for (Path p : stream.toList()) {
                try {
                    if (Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) {
                        Files.delete(p);
                        count++;
                    }
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
        return count;
    }
}
