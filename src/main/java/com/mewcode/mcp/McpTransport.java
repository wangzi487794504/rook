package com.mewcode.mcp;

public interface McpTransport {
    String send(String jsonRpcRequest);
    void close();
}
