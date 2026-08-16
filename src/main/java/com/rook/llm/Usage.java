package com.rook.llm;

/**
 * Token 用量统计，含缓存命中信息。
 *
 * @author wangzijie
 * @since 0.3.0
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public record Usage(
        int inputTokens,
        int outputTokens,
        int cacheReadInputTokens,
        int cacheCreationInputTokens) {

    public static final Usage ZERO = new Usage(0, 0, 0, 0);

    /** 向后兼容构造函数 */
    /** @return 方法返回值 */
    public Usage(int inputTokens, int outputTokens) {
        this(inputTokens, outputTokens, 0, 0);
    }

    /** 累加另一个 Usage 返回新实例。 */
    /** @return 方法返回值 */
    public Usage add(Usage other) {
        return new Usage(
                this.inputTokens + other.inputTokens,
                this.outputTokens + other.outputTokens,
                this.cacheReadInputTokens + other.cacheReadInputTokens,
                this.cacheCreationInputTokens + other.cacheCreationInputTokens);
    }
}
