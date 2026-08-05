package com.mewcode.hook;

import com.mewcode.tools.ToolCall;

/**
 * 条件匹配上下文 —— 从 HookPayload 提取各字段供 Matcher 匹配。
 */
public class HookContext {
    private final HookPayload payload;

    public HookContext(HookEvent event, HookPayload payload) { this.payload = payload; }
    public HookEvent event() { return payload != null ? payload.event() : null; }

    public String getField(String name) {
        if (payload == null) return null;
        return switch (name) {
            case "tool" -> payload.toolCall() != null ? payload.toolCall().name() : null;
            case "command" -> extractParam("command");
            case "filePath" -> extractParam("filePath");
            case "pattern" -> extractParam("pattern");
            case "prompt" -> payload.userPrompt();
            case "compactReason" -> payload.compactReason();
            case "error" -> payload.errorMsg();
            default -> null;
        };
    }

    /** 从 HookPayload 的 parameters（通过 ToolCall.input）中提取参数值 */
    private String extractParam(String key) {
        if (payload == null) return null;
        if (payload.toolCall() != null && payload.toolCall().input() != null) {
            Object val = payload.toolCall().input().get(key);
            if (val != null) return val.toString();
        }
        return null;
    }
}
