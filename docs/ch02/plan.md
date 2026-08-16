---
title: "plan"
author: wangzijie
date: 2026-08-05
---

# MewCode 第2章 - 工具系统 Plan

## 架构概览

本章在 ch01 基础上新增 `tools/` 包，对 `llm/` 和 `cli/` 两个包做扩展。

新增/变更的模块：

                              ┌─────────────────┐
                              │    CLI / REPL     │  ← 修改：工具调用链路
                              └────────┬────────┘
                                       │
                          ┌────────────┼────────────┐
                          ▼            ▼            ▼
                    ┌──────────┐ ┌──────────┐ ┌──────────┐
                    │ tools/    │ │ session/  │ │  llm/    │ ← 修改：tools 参数
                    │ (新增)   │ │ (不变)   │ │          │
                    └──────────┘ └──────────┘ └──────────┘
                         │                          │
              ┌──────────┼──────────┐               │
              ▼          ▼          ▼               │
         读文件   写文件    改文件   命令执行  Glob  Grep

新增组件：
- `tools/Tool.java`          — 工具抽象接口
- `tools/ToolResult.java`    — 工具执行结果（成功内容和错误信息）
- `tools/ToolCall.java`      — 从模型解析出的工具调用
- `tools/ToolRegistry.java`  — 工具注册中心
- `tools/impl/ReadFileTool.java`  — 读文件
- `tools/impl/WriteFileTool.java` — 写文件
- `tools/impl/EditFileTool.java`  — 改文件
- `tools/impl/ExecCommandTool.java` — 执行命令
- `tools/impl/GlobTool.java`      — glob 搜索
- `tools/impl/GrepTool.java`      — grep 搜索

修改组件：
- `llm/StreamCallback.java`  — 新增 onToolCall 回调
- `llm/LlmProvider.java`     — stream() 增加 ToolRegistry 参数
- `llm/AnthropicProvider.java` — 请求带 tools，解析 tool_use 块
- `llm/OpenAiProvider.java` — 请求带 tools，解析 tool_calls
- `core/App.java`            — 创建 ToolRegistry 并注入
- `cli/Repl.java`            — 工具执行调度链路

## 核心数据结构

### Tool 接口（新增）

```java
interface Tool {
    /** 工具名称，如 "read_file" */
    String name();
    /** 工具功能描述，给模型看的 */
    String description();
    /** 参数 JSON Schema，告诉模型参数怎么填 */
    Map<String, Object> inputSchema();
    /** 执行工具，接收模型填的参数，返回结构化结果 */
    ToolResult execute(Map<String, Object> params);
}
```

### ToolResult（新增）

```java
record ToolResult(
    boolean success,     // 是否成功
    String content       // 成功时是结果内容，失败时是错误描述
) {
    static ToolResult ok(String content) { return new ToolResult(true, content); }
    static ToolResult error(String msg) { return new ToolResult(false, msg); }
}
```

### ToolCall（新增，解析自 API 响应）

```java
record ToolCall(
    String id,              // API 返回的 tool_use id
    String name,            // 工具名称
    Map<String, Object> input  // 模型填的参数
) {}
```

### ToolRegistry（新增）

```java
class ToolRegistry {
    void register(Tool tool);           // 注册工具
    Tool get(String name);              // 按名查找
    List<Map<String, Object>> toToolDefinitions();  // 输出为 Anthropic API 格式
    Set<String> names();                // 所有工具名
}
```

### StreamCallback 变更（修改）

```java
interface StreamCallback {
    void onToken(String token);
    /** 模型要求调用工具时回调（非文本 token） */
    default void onToolCall(ToolCall toolCall) {}
}
```

### LlmProvider 变更（修改）

stream 方法增加 ToolRegistry 参数：

```java
void stream(List<ChatMessage> messages, ToolRegistry tools, StreamCallback callback)
        throws LlmException;
```

### ChatMessage 不变

保持 `record(String role, String content)` 不变。工具结果回灌时，
由各 Provider 内部构造 API 所需的 content block 结构（tool_result）。

## 模块设计

### 包结构

```
tools/
├── Tool.java              — 接口
├── ToolResult.java        — 结果 record
├── ToolCall.java          — 解析出的工具调用 record
├── ToolRegistry.java      — 注册中心
└── impl/
    ├── ReadFileTool.java   — 读文件
    ├── WriteFileTool.java  — 写文件（原子写入）
    ├── EditFileTool.java   — 改文件（唯一匹配替换）
    ├── ExecCommandTool.java — 执行命令（带超时）
    ├── GlobTool.java       — Glob 文件搜索
    └── GrepTool.java       — Grep 代码搜索
```

### 模块 A：tools/ — 工具接口与注册中心

**职责：** 定义工具契约，集中管理六个工具实例，输出 API 格式的工具定义

**对外接口：**
- `Tool` 接口 — 见上文
- `ToolRegistry` — `register()` / `get()` / `toToolDefinitions()`

**依赖：** 无外部模块依赖

### 模块 B：tools/impl/ — 六个工具实现

**职责：** 每种工具负责一类文件/命令操作，统一返回 ToolResult

**对外接口：** 均实现 `Tool` 接口

**依赖：** 仅依赖 `Tool` 和 `ToolResult`，不依赖 LLM 或 CLI 层

**各工具要点：**

| 工具 | 关键逻辑 |
|------|---------|
| ReadFileTool | `Files.readString()`，支持 offset/limit 行范围，大文件截断 100KB |
| WriteFileTool | 先写 `.tmp` 再 `Files.move` (ATOMIC_MOVE)，父目录自动创建 |
| EditFileTool | 读全文 → 搜索 oldString → 0次报"未找到"、多次报"匹配N处" → 唯一则替换 → 写回 |
| ExecCommandTool | `ProcessBuilder`，非交互式，合并 stderr，超时 `process.waitFor(30, SECONDS)` → 超时则 `destroyForcibly()` |
| GlobTool | `Files.find()` + `PathMatcher("glob:" + pattern)`，限制结果 500 条 |
| GrepTool | 逐行正则搜索，支持 `-i` 大小写、`include` 文件过滤，结果上限 200 条 |

### 模块 C：llm/ — Provider 扩展

**职责：** 请求体中携带 tools 定义，流式解析 tool_use/tool_calls 事件

**变更：**

AnthropicProvider:
- 请求体新增 `"tools"` 字段
- SSE 解析新增处理 `content_block_start(type=tool_use)`：记录 tool_use_id + tool_name
- 后续 `content_block_delta(type=input_json_delta)` → 拼接 JSON 碎片
- `content_block_stop` → 回调 `onToolCall()`

OpenAiProvider:
- 请求体新增 `"tools"` 字段（OpenAI 格式：`type: function` + `function: {name, description, parameters}`）
- SSE 解析处理 `choices[0].delta.tool_calls`：通过 index 归并碎片，拼接 function.arguments JSON
- 收集完成后回调 `onToolCall()`

### 模块 D：cli/ — Repl 工具调度

**职责：** 介于 Provider 和 ToolRegistry 之间，串联工具调用链路

**processMessage 新流程：**

```
用户输入 "帮我读 pom.xml"
  │
  ├─ 1. session.addMessage(userMsg)
  ├─ 2. provider.stream(messages, toolRegistry, callback)
  │      │
  │      ├─ 如果是文本 → callback.onToken(text) → 逐字打印
  │      └─ 如果是 tool_use → callback.onToolCall(toolCall)
  │             │
  │             ├─ 打印 "[调用工具: read_file ...]"
  │             ├─ 3. Tool tool = toolRegistry.get(toolCall.name())
  │             ├─ 4. ToolResult result = tool.execute(toolCall.input)
  │             ├─ 5. 打印执行结果摘要
  │             ├─ 6. 构造 tool_result 消息，加入消息历史
  │             └─ 7. provider.stream(messages, toolRegistry, callback)
  │                    │
  │                    └─ 这次只返回文本 → 逐字打印
  │
  └─ 8. session.addMessage(assistantMsg)
```

## 文件组织

```
src/main/java/com/mewcode/
├── cli/
│   ├── Main.java                  — 不变
│   └── Repl.java                  — 修改：工具调度链路
├── config/                        — 不变
├── core/
│   └── App.java                   — 修改：创建 ToolRegistry 并注入
├── llm/
│   ├── ChatMessage.java           — 不变
│   ├── LlmException.java          — 不变
│   ├── LlmProvider.java           — 修改：stream() 加 ToolRegistry 参数
│   ├── StreamCallback.java        — 修改：新增 onToolCall()
│   ├── AnthropicProvider.java     — 修改：tools 请求 + tool_use 解析
│   ├── OpenAiProvider.java        — 修改：tools 请求 + tool_calls 解析
│   └── ProviderFactory.java       — 不变
├── session/
│   └── Session.java               — 不变
└── tools/                         — ★ 新增
    ├── Tool.java
    ├── ToolCall.java
    ├── ToolResult.java
    ├── ToolRegistry.java
    └── impl/
        ├── ReadFileTool.java
        ├── WriteFileTool.java
        ├── EditFileTool.java
        ├── ExecCommandTool.java
        ├── GlobTool.java
        └── GrepTool.java

src/test/java/com/mewcode/
├── tools/
│   ├── ToolRegistryTest.java
│   └── impl/
│       ├── ReadFileToolTest.java
│       ├── WriteFileToolTest.java
│       ├── EditFileToolTest.java
│       ├── ExecCommandToolTest.java
│       ├── GlobToolTest.java
│       └── GrepToolTest.java
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 工具元信息格式 | JSON Schema (Draft 7) | Anthropic/OpenAI 都支持，工具定义可跨协议复用 |
| 写文件原子性 | 写 tmp + ATOMIC_MOVE | JDK 内置，不需额外库，断电不损坏原文件 |
| 命令超时实现 | `Process.waitFor(timeout)` + `destroyForcibly()` | JDK 内置，简单可靠 |
| Edit 匹配策略 | 逐字精确匹配，非正则 | 消除正则转义歧义，唯一匹配可确定替换位置 |
| Glob 实现 | `java.nio.file.PathMatcher("glob:...")` | JDK 原生，零依赖 |
| Grep 实现 | 逐行 `Pattern.compile` + `Files.lines` | 支持正则，性能足够 |
| 输出截断 | 100KB 硬截断，末尾加 `...(已截断)` | 防止大文件撑爆上下文窗口 |
| tool_use JSON 拼接 | Provider 内部用 StringBuilder 累积 input_json_delta | 符合 Anthropic SSE 协议 |
| tool_result 回灌 | 由 Repl 构造结果消息，追加到 messages 后再次调用 stream() | Provider 只管 API 协议，调度逻辑在 Repl |
| 工具定义注入 | App 启动时创建 ToolRegistry → 传给 Provider.stream() | 灵活替换工具集 |
