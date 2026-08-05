# MewCode 第6章 - MCP 客户端 Plan

## 架构概览

新增 `mcp/` 包。MCP Manager 启动时加载配置、初始化所有 Server、发现工具并注册。

                        App (启动时)
                           │
                           ▼
                    ┌─────────────┐
                    │  McpManager  │
                    │ .discover()  │
                    └──────┬──────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
        ┌──────────┐ ┌──────────┐ ┌──────────┐
        │ Server A  │ │ Server B  │ │ Server C  │
        │ (stdio)   │ │ (stdio)   │ │ (http)    │
        └─────┬────┘ └─────┬────┘ └─────┬────┘
              │            │            │
        ┌─────▼────┐ ┌─────▼────┐ ┌─────▼────┐
        │Transport │ │Transport │ │Transport │
        │ Stdio    │ │ Stdio    │ │ HTTP     │
        └──────────┘ └──────────┘ └──────────┘
              │            │            │
              └────────────┼────────────┘
                           ▼
                    ┌─────────────┐
                    │ ToolRegistry│
                    │  内置+MCP   │
                    └─────────────┘

新增: mcp/
├── McpManager.java      — 总控
├── McpConfig.java       — 配置+展开
├── McpTransport.java    — 传输接口
├── StdioTransport.java  — stdin/stdout
├── HttpTransport.java   — HTTP+SSE
├── McpSession.java      — 单Server会话(串行队列)
├── McpToolAdapter.java  — 远端→Tool接口
└── JsonRpc.java         — JSON-RPC消息

变更: core/App.java

## 核心数据结构

### McpServerConfig
record(name, type, command, args, env, url, headers)

### McpTransport
send(String jsonRpc) → String response; close()

### McpSession
initialize() → capabilities; listTools() → List<McpToolDef>; callTool(name, params) → ToolResult
内部 BlockingQueue 串行化

### McpToolAdapter
implements Tool { execute → session.callTool() }

## 技术决策
| 决策点 | 选择 | 理由 |
|--------|------|------|
| JSON-RPC | 手写 Jackson | 3种请求，不引库 |
| 进程管理 | ProcessBuilder | JDK内置 |
| HTTP | java.net.http.HttpClient | 已有 |
| 配置展开 | 正则 \$\{(\w+)\} | 简单 |
| 超时 | 复用 ToolExecutor | 无需独立 |
