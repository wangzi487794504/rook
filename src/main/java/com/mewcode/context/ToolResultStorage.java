package com.mewcode.context;

import com.mewcode.tools.ToolCall;
import com.mewcode.tools.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * 第1层：工具结果存盘替换 —— 超大结果存入磁盘，对话中替换为稳定预览。
 * 预览格式逐字节一致 → 保证 prompt cache 命中率。
 */
public class ToolResultStorage {

    private final Path contextDir;
    private final int threshold;

    public ToolResultStorage(Path contextDir, int threshold) {
        this.contextDir = contextDir;
        this.threshold = threshold;
    }

    /** 检查是否需要存盘，返回最终放入对话的文本 */
    public String maybeStore(ToolCall call, ToolResult result) {
        String content = result.content();
        if (content == null || content.length() < threshold) return content;
        return storeToFile(call, content);
    }

    private String storeToFile(ToolCall call, String content) {
        try {
            Files.createDirectories(contextDir);
            String ts = String.valueOf(Instant.now().toEpochMilli());
            Path tmp = contextDir.resolve(ts + ".tmp");
            Files.writeString(tmp, content);
            Path target = contextDir.resolve(ts + ".txt");
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);

            return buildPreview(target, content);
        } catch (IOException e) {
            return content; // 存盘失败，直接返回原文
        }
    }

    /** 构造稳定预览（逐字节一致 → cache 命中） */
    static String buildPreview(Path file, String content) {
        int previewLen = Math.min(200, content.length());
        return "[工具结果已存盘: " + file + "，大小 " + content.length()
                + " 字节，预览: " + content.substring(0, previewLen) + "...]";
    }
}
