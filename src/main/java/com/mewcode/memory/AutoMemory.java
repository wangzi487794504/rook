package com.mewcode.memory;

import com.mewcode.llm.*;
import com.mewcode.memory.MemoryIndex.MemoryNote;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 自动记忆 —— Agent Loop 结束后异步调用 LLM 更新四类笔记。
 *
 * @author MewCode
 * @since 0.8.0
 */
public class AutoMemory {

    private static final String MEMORY_PROMPT = """
            你是一个记忆管理助手。根据以下对话，判断是否需要更新用户记忆。

            现有笔记:
            %s

            对话:
            %s

            如果有值得记录的信息，输出 JSON 数组，每项:
            {"type":"user|feedback|project|reference","title":"...","description":"...","action":"add|update|delete"}

            如果没有值得记录的，输出空数组 []。只输出 JSON，不要任何解释。""";

    private final Path memoryDir;
    private final MemoryIndex index;

    public AutoMemory(Path memoryDir, MemoryIndex index) {
        this.memoryDir = memoryDir;
        this.index = index;
    }

    /** 异步更新记忆，不阻塞 */
    public CompletableFuture<Void> updateAsync(List<ChatMessage> history, LlmProvider provider) {
        return CompletableFuture.runAsync(() -> {
            try {
                update(history, provider);
            } catch (Exception ignored) {
                // 静默失败
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void update(List<ChatMessage> history, LlmProvider provider) {
        // 构建对话摘要
        StringBuilder conversation = new StringBuilder();
        for (ChatMessage m : history) {
            if (m.content() != null && m.toolUseId() == null) {
                conversation.append("[").append(m.role()).append("]: ")
                        .append(m.content()).append("\n");
            }
        }

        // 加载现有记忆
        List<MemoryNote> existing = loadNotes();
        StringBuilder existingText = new StringBuilder();
        for (MemoryNote n : existing) {
            existingText.append("- [").append(n.type()).append("] ").append(n.title())
                    .append(": ").append(n.description()).append("\n");
        }

        String prompt = String.format(MEMORY_PROMPT, existingText, conversation);
        List<ChatMessage> msgs = List.of(new ChatMessage("user", prompt));

        try {
            String resp = provider.complete(msgs);
            // 解析 JSON 数组
            String json = resp.trim();
            if (json.startsWith("```")) json = json.replaceAll("```\\w*\\n?", "").replace("```", "");

            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
            List<Map<String, Object>> actions = m.readValue(json, List.class);

            for (Map<String, Object> action : actions) {
                String type = (String) action.get("type");
                String title = (String) action.get("title");
                String desc = (String) action.get("description");
                String act = (String) action.get("action");

                Path dir = memoryDir.resolve(type);
                Path file = dir.resolve(slugify(title) + ".md");
                Files.createDirectories(dir);

                if ("delete".equals(act)) {
                    Files.deleteIfExists(file);
                } else {
                    // add / update
                    String md = "---\nname: " + slugify(title)
                            + "\ndescription: " + desc + "\ntype: " + type + "\n---\n\n" + desc;
                    Files.writeString(file, md);
                }
            }

            // 重建索引
            index.write(loadNotes());
        } catch (Exception e) {
            // 静默失败
        }
    }

    private List<MemoryNote> loadNotes() {
        List<MemoryNote> notes = new ArrayList<>();
        if (!Files.exists(memoryDir)) return notes;
        try (var stream = Files.walk(memoryDir)) {
            stream.filter(f -> f.toString().endsWith(".md") && !f.getFileName().toString().equals("MEMORY.md"))
                    .forEach(f -> {
                        try {
                            String content = Files.readString(f);
                            String title = extractFrontmatter(content, "name");
                            String desc = extractFrontmatter(content, "description");
                            String type = extractFrontmatter(content, "type");
                            if (title != null) {
                                notes.add(new MemoryNote(title, desc != null ? desc : "",
                                        f, type != null ? type : ""));
                            }
                        } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
        return notes;
    }

    private static String slugify(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", "-")
                .replaceAll("^-|-$", "");
    }

    private static String extractFrontmatter(String md, String key) {
        int start = md.indexOf("---\n");
        if (start < 0) return null;
        int end = md.indexOf("---\n", start + 4);
        if (end < 0) return null;
        String fm = md.substring(start + 4, end);
        for (String line : fm.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equals(key)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }
}
