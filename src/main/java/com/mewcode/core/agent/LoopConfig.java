package com.mewcode.core.agent;

/**
 * Agent Loop 配置 —— 控制最大迭代次数、并发上限、连续错误容限。
 *
 * @author MewCode
 * @since 0.3.0
 */
public record LoopConfig(
        int maxIterations,
        int maxParallelReads,
        int consecutiveErrorLimit) {

    public LoopConfig() {
        this(25, 5, 3);
    }
}
