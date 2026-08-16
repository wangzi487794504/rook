package com.rook.core.agent;

/**
 * Agent Loop 配置 —— 控制最大迭代次数、并发上限、连续错误容限。
 *
 * @author wangzijie
 * @since 0.3.0
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public record LoopConfig(
        int maxIterations,
        int maxParallelReads,
        int consecutiveErrorLimit) {

    /** @return 方法返回值 */
    public LoopConfig() {
        this(25, 5, 3);
    }
}
