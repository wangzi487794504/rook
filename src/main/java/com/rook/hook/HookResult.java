package com.rook.hook;

/** Hook 执行结果 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public record HookResult(boolean ok, boolean intercept, String message) {
    public static HookResult ok(String msg) { return new HookResult(true, false, msg); }
    public static HookResult error(String msg) { return new HookResult(false, false, msg); }
    public static HookResult intercept(String reason) { return new HookResult(true, true, reason); }
    public static HookResult inject(String text) { return new HookResult(true, false, text); }
}
