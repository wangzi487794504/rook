package com.rook.core.agent;

/**
 * Agent Loop 当前状态。
 *
 * @author wangzijie
 * @since 0.3.0
 */
public enum LoopState {
    RUNNING,
    CANCELLED,
    COMPLETED,
    MAX_ITERATIONS,
    ERROR
}
