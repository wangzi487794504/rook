package com.rook.hook;

/** 匹配器 —— 精确/反向/正则/glob，复用于权限规则和 Hook 条件 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public record HookMatcher(String field, String mode, String pattern, String nestedField) {
    public boolean matches(String value) {
        if (value == null) return false;
        return switch (mode) {
            case "exact" -> value.equals(pattern);
            case "not" -> !value.equals(pattern);
            case "regex" -> java.util.regex.Pattern.compile(pattern)
                    .matcher(value).find(); // find() 部分匹配，非整串
            case "glob" -> java.nio.file.FileSystems.getDefault()
                    .getPathMatcher("glob:" + pattern)
                    .matches(java.nio.file.Path.of(value));
            default -> false;
        };
    }
}
