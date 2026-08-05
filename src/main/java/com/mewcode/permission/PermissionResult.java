package com.mewcode.permission;

/** 权限检查结果：哪一层、什么判定、原因是什么 */
public record PermissionResult(
        PermissionVerdict verdict,
        String reason,
        String layer) {

    public static PermissionResult allow() {
        return new PermissionResult(PermissionVerdict.ALLOW, "", "");
    }

    public static PermissionResult deny(String reason, String layer) {
        return new PermissionResult(PermissionVerdict.DENY, reason, layer);
    }

    public static PermissionResult ask(String reason) {
        return new PermissionResult(PermissionVerdict.ASK, reason, "mode");
    }

    public boolean isAllowed() { return verdict == PermissionVerdict.ALLOW; }
    public boolean isDenied() { return verdict == PermissionVerdict.DENY; }
    public boolean isAsk() { return verdict == PermissionVerdict.ASK; }
}
