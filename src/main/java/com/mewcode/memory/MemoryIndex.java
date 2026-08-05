package com.mewcode.memory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆索引 —— MEMORY.md 管理，限制 200 行 / 25KB。
 *
 * @author MewCode
 * @since 0.8.0
 */
public class MemoryIndex {

    private static final int MAX_LINES = 200;
    private static final int MAX_BYTES = 25 * 1024;

    private final Path indexPath;

    public MemoryIndex(Path indexPath) {
        this.indexPath = indexPath;
    }

    /** 读取并注入到 System Prompt */
    public String loadForPrompt() {
        if (!Files.exists(indexPath)) return "";
        try {
            String content = Files.readString(indexPath);
            if (content.length() > MAX_BYTES) {
                content = content.substring(0, MAX_BYTES) + "\n...(索引已截断)";
            }
            return content;
        } catch (IOException e) {
            return "";
        }
    }

    /** 写入索引 */
    public void write(List<MemoryNote> notes) throws IOException {
        Files.createDirectories(indexPath.getParent());
        StringBuilder sb = new StringBuilder();
        for (MemoryNote note : notes) {
            String line = "- [" + note.title() + "](" + note.file().getFileName() + ") — "
                    + note.description() + "\n";
            sb.append(line);
        }
        Files.writeString(indexPath, sb.toString());
    }

    public record MemoryNote(String title, String description, Path file, String type) {}
}
