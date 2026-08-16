package com.rook.permission;

import com.rook.tools.ToolCall;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 第1层：高危命令黑名单 —— 内置正则，不可配置绕过。
 *
 * @author wangzijie
 * @since 0.5.0
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class Blacklist {

    private static final List<Pattern> DANGEROUS = List.of(
            Pattern.compile("rm\\s+.*-(r|rf|fr)\\s+/"),
            Pattern.compile("mkfs"),
            Pattern.compile("dd\\s+if="),
            Pattern.compile(">\\s*/dev/sd"),
            Pattern.compile("chmod\\s+777\\s+/"),
            Pattern.compile("chown\\s+-R\\s+/"),
            Pattern.compile(Pattern.quote(":(){ :|:& };:")),  // fork bomb
            Pattern.compile("shutdown|reboot|halt|poweroff")
    );

    /** @return 方法返回值 */
    public PermissionResult check(ToolCall call) {
        if (!"exec_command".equals(call.name())) {
            return PermissionResult.allow(); // 只拦截命令执行
        }

        String command = String.valueOf(call.input().getOrDefault("command", ""));
        for (Pattern p : DANGEROUS) {
            if (p.matcher(command).find()) {
                return PermissionResult.deny(
                        "高危命令被黑名单拦截: " + command, "blacklist");
            }
        }
        return PermissionResult.allow(); // 穿透
    }
}
