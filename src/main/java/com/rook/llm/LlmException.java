package com.rook.llm;

/**
 * LLM API 调用异常，包含错误类型和用户可读消息。
 *
 * @author wangzijie
 * @since 0.1.0
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class LlmException extends Exception {

    private final int statusCode;

    /**
     * @param message    用户可读的错误描述
     * @param statusCode HTTP 状态码（-1 表示非 HTTP 错误）
     */
    /** @return 方法返回值 */
    public LlmException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    /** @return 方法返回值 */
    public LlmException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
