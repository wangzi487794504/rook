# MewCode 第6章 - MCP 客户端 Spec

## 背景

ch05 完成后，MewCode 拥有六种内置工具和完整的权限系统。但实际开发中用户还需要更多工具。MCP 标准化方案：工具由外部 Server 提供，Client 通过 JSON-RPC 2.0 协议发现和调用。

## 目标

- 实现 MCP 客户端，启动时自动发现并注册外部 Server 的工具
- 支持 stdio（本地子进程）和 Streamable HTTP（远程）两种传输
- JSON-RPC 2.0 协议处理，请求/响应按 id 配对
- 外部工具通过适配层包装成 Tool 接口，Agent 调用时无感
- 多个 Server 独立管理连接和生命周期，单 Server 故障不影响其他
- 配置来自两层合并的 YAML（用户级 + 项目级），支持 ${VAR} 展开
- 每个 Server 内部串行，不同 Server 间可并发

## 功能需求

### 传输层
- F1: stdio 传输 — 启动本地子进程，通过 stdin/stdout 收发 JSON-RPC 消息
- F2: Streamable HTTP 传输 — POST 请求，接收 SSE 或纯 JSON 响应
- F3: stdio 配置: command/args/env，值支持 ${VAR} 展开
- F4: HTTP 配置: url/headers，值支持 ${VAR} 展开

### 协议层
- F5: JSON-RPC 2.0 格式请求/响应
- F6: 按 id 字段匹配请求和响应
- F7: 处理成功和错误两类响应

### 会话管理
- F8: initialize 握手 → notifications/initialized
- F9: tools/list 列出工具
- F10: tools/call 调用工具

### 适配层
- F11: McpToolAdapter 包装成 Tool 接口
- F12: Agent 调用时无感

### 连接管理
- F13: 每 Server 内部串行，Server 间可并发
- F14: 单 Server 故障不影响其他
- F15: 启动时并行初始化

### 配置
- F16: 用户级 + 项目级两层合并
- F17: servers map YAML 格式
- F18: ${VAR} / ${VAR:default} 环境变量展开

## 非功能需求
- N1-N4 同上

## 验收标准
- AC1-AC10 同上
