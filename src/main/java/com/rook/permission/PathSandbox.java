package com.rook.permission;

import com.rook.tools.ToolCall;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * 第2层：路径沙箱 —— 限制文件操作在项目根目录内，解析符号链接防逃逸。
 *
 * @author wangzijie
 * @since 0.5.0
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class PathSandbox {

    private static final Set<String> FILE_TOOLS = Set.of(
            "read_file", "write_file", "edit_file", "glob", "grep");
    private static final Set<String> PATH_KEYS = Set.of("filePath", "path");

    private final Path projectRoot;

    /** @return 方法返回值 */
    public PathSandbox(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
    }

    /** @return 方法返回值 */
    public PermissionResult check(ToolCall call) {
        if (!FILE_TOOLS.contains(call.name())) {
            return PermissionResult.allow(); // 非文件工具，穿透
        }

        // 提取路径参数
        String pathStr = null;
        for (String key : PATH_KEYS) {
            Object val = call.input().get(key);
            if (val instanceof String s && !s.isBlank()) {
                pathStr = s;
                break;
            }
        }
        if (pathStr == null) {
            return PermissionResult.allow(); // 无路径参数，穿透（如只读 pattern 的 glob）
        }

        try {
            // 解析为真实路径（解析符号链接）
            Path target = projectRoot.resolve(pathStr).normalize();
            if (Files.exists(target)) {
                target = target.toRealPath();
            }

            // 前缀判断
            Path rootReal = Files.exists(projectRoot) ? projectRoot.toRealPath() : projectRoot;
            if (!target.startsWith(rootReal)) {
                return PermissionResult.deny(
                        "路径超出项目范围: " + pathStr + " → " + target, "sandbox");
            }
            return PermissionResult.allow();
        } catch (Exception e) {
            return PermissionResult.deny(
                    "路径检查失败: " + e.getMessage(), "sandbox");
        }
    }
}
