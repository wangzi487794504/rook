package com.rook.mcp;

/**
 * @author wangzijie
 * @since 0.1.0
 */
public interface McpTransport {
    String send(String jsonRpcRequest);
    void close();
}
