package com.mewcode.hook;

import com.mewcode.tools.ToolCall;
import com.mewcode.tools.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * 事件 Payload —— 每个事件携带固定结构数据，供条件匹配和动作用。
 */
public record HookPayload(
        HookEvent event,
        String sessionId,         // SESSION_*
        String userPrompt,        // USER_PROMPT_SUBMIT, PRE_USER_MESSAGE
        ToolCall toolCall,        // PRE_TOOL_USE, POST_TOOL_USE
        ToolResult toolResult,    // POST_TOOL_USE
        boolean toolDenied,       // POST_TOOL_USE: 权限被 Deny
        List<Map<String, Object>> messages, // PRE_USER_MESSAGE
        String compactReason,     // PRE_COMPACT
        String notificationText,  // NOTIFICATION
        String errorMsg           // 各种错误
) {
    public static HookPayload forSession(HookEvent event, String sessionId) {
        return new HookPayload(event, sessionId, null, null, null, false, null, null, null, null);
    }
    public static HookPayload forUserPrompt(String prompt) {
        return new HookPayload(HookEvent.USER_PROMPT_SUBMIT, null, prompt, null, null, false, null, null, null, null);
    }
    public static HookPayload forTool(HookEvent event, ToolCall call, ToolResult result, boolean denied) {
        return new HookPayload(event, null, null, call, result, denied, null, null, null, null);
    }
    public static HookPayload forCompact(String reason) {
        return new HookPayload(HookEvent.PRE_COMPACT, null, null, null, null, false, null, reason, null, null);
    }
    public static HookPayload forNotification(String text) {
        return new HookPayload(HookEvent.NOTIFICATION, null, null, null, null, false, null, null, text, null);
    }
    public static HookPayload forStop() {
        return new HookPayload(HookEvent.STOP, null, null, null, null, false, null, null, null, null);
    }
}
