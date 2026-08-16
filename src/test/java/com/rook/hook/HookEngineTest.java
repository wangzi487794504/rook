package com.rook.hook;

import com.rook.tools.ToolCall;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HookEngineTest {

    @Test
    void should_intercept_dangerous_command() {
        HookEngine engine = new HookEngine(
                Paths.get(".rook/hooks.yaml"),
                Paths.get(System.getProperty("user.home"), ".rook/hooks.yaml"));

        HookPayload payload = HookPayload.forTool(HookEvent.PRE_TOOL_USE,
                new ToolCall("call_01", "exec_command",
                        Map.of("command", "rm -rf /tmp/test")),
                null, false);

        String result = engine.fire(HookEvent.PRE_TOOL_USE, payload);
        assertNotNull(result, "应该拦截 rm -rf 命令");
        assertTrue(result.contains("BLOCKED"), "拦截原因应包含 BLOCKED: " + result);
    }

    @Test
    void should_not_intercept_safe_command() {
        HookEngine engine = new HookEngine(
                Paths.get(".rook/hooks.yaml"),
                Paths.get(System.getProperty("user.home"), ".rook/hooks.yaml"));

        HookPayload payload = HookPayload.forTool(HookEvent.PRE_TOOL_USE,
                new ToolCall("call_02", "exec_command",
                        Map.of("command", "echo hello")),
                null, false);

        String result = engine.fire(HookEvent.PRE_TOOL_USE, payload);
        assertNull(result, "安全命令不应被拦截");
    }
}
