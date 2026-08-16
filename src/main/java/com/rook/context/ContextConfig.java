package com.rook.context;

/** 上下文管理配置 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public record ContextConfig(
        int maxToolResultBytes,   // 单条工具结果存盘阈值, 默认 8KB
        int contextWindow,        // 模型上下文窗口, 默认 200K
        int autoMargin,           // 自动触发余量, 默认 13K
        int manualMargin,         // 手动触发余量, 默认 3K
        boolean enabled           // 开关, 默认 true
) {
    /** @return 方法返回值 */
    public ContextConfig() { this(8192, 200_000, 13_000, 3_000, true); }
    /** @return 方法返回值 */
    public ContextConfig withEnabled(boolean e) { return new ContextConfig(maxToolResultBytes, contextWindow, autoMargin, manualMargin, e); }
}
