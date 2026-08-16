package com.rook.permission;

import com.rook.tools.ToolCall;
import org.jline.reader.LineReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 第5层：人在回路 —— 模式判 Ask 时暂停，等待用户确认。
 *
 * @author wangzijie
 * @since 0.5.0
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class HumanInTheLoop {

    private final Path localRulesPath;

    /** @return 方法返回值 */
    public HumanInTheLoop(Path localRulesPath) {
        this.localRulesPath = localRulesPath;
    }

    /**
     * 弹出确认，阻塞等待用户选择。
     * @return Allow 或 Deny
     */
    /** @return 方法返回值 */
    public PermissionResult ask(ToolCall call, String reason, LineReader reader) {
        var w = reader.getTerminal().writer();
        try {
            w.println();
            w.println("═══════════════════════════════════════");
            w.println("  权限确认");
            w.println("═══════════════════════════════════════");
            w.println("  工具: " + call.name());
            w.println("  参数: " + truncate(call.input().toString(), 80));
            w.println("  原因: " + reason);
            w.println("───────────────────────────────────────");
            w.println("  1. 允许本次      (默认)");
            w.println("  2. 永久允许      (写入本地规则)");
            w.println("  3. 拒绝本次");
            w.println("═══════════════════════════════════════");
            w.print("  请选择 [1/2/3]: ");
            w.flush();

            // 读取单字符选择
            int ch;
            try {
                ch = reader.getTerminal().reader().read();
            } catch (IOException e) {
                ch = '1'; // fallback
            }

            w.println();

            return switch (ch) {
                case '2' -> {
                    // 永久允许 → 写入本地规则
                    savePermanentRule(call);
                    yield PermissionResult.allow();
                }
                case '3' -> PermissionResult.deny("用户拒绝", "human");
                default -> PermissionResult.allow(); // 1 或回车默认允许本次
            };
        } finally {
            // 确保换行
            w.println();
            w.flush();
        }
    }

    private void savePermanentRule(ToolCall call) {
        try {
            Files.createDirectories(localRulesPath.getParent());
            String param = call.input().values().stream()
                    .filter(v -> v instanceof String)
                    .map(Object::toString)
                    .findFirst().orElse("*");
            // YAML 安全：缩进 2 空格在 rules: 下
            String yamlLine = "  - tool: " + call.name() + "\n    pattern: '"
                    + param.replace("'", "''") + "'\n    action: allow\n";
            Files.writeString(localRulesPath, yamlLine, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
