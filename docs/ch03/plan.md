---
title: "plan"
author: wangzijie
date: 2026-08-05
---

# MewCode 第3章 - Agent Loop Plan

## 架构概览

本章核心是新增 `core/agent/` 子包，将 ch02 中 Repl 手写的"一次工具调用"
提升为解耦的、异步的、可取消的多轮 Agent Loop。

新旧对比：

  ch02:  Repl → 调一次 stream() → 有 tool? → 执行 → 再调一次 → 结束
  ch03:  Repl → AgentLoop.run() → while(未停止) { LLM → 解析 → ToolExecutor → 回灌 }

新增组件：

  core/agent/
  ├── AgentLoop.java        — ReAct 循环引擎
  ├── AgentEvent.java       — 事件类型定义
  ├── LoopConfig.java       — 循环配置（最大轮数、超时等）
  └── LoopState.java        — 循环状态（运行中/已取消/已完成/错误）

  llm/
  └── StreamCollector.java  — 流式双路收集器

  tools/
  └── ToolExecutor.java     — 保序分批并发执行器

变更组件：

  cli/Repl.java             — 重构：用 AgentLoop 替换手写调度
  core/App.java             — 创建 AgentLoop + 注入配置
  llm/StreamCallback.java   — 增强：支持多 tool_use 回调 + onUsage
  llm/LlmProvider.java      — 新增 lastUsage()
  llm/ChatMessage.java      — 扩展 toolResult 支持
  llm/AnthropicProvider.java — 解析 usage 事件
  llm/OpenAiProvider.java   — 解析 usage 事件

## 核心数据结构

### AgentEvent（新增）

```java
enum AgentEventType { TEXT, TOOL_CALL, TOOL_RESULT, TOKEN_USAGE, PROGRESS, ERROR, DONE }

record AgentEvent(
    AgentEventType type,
    String text,              // TEXT || ERROR || DONE 时用
    ToolCall toolCall,        // TOOL_CALL 时用
    ToolResult toolResult,    // TOOL_RESULT 时用
    int iteration,            // 当前轮次
    int totalIterations       // 总轮次上限
) {}
```

### LoopConfig（新增）

```java
record LoopConfig(
    int maxIterations,       // 最大循环次数，默认 25
    int maxParallelReads,    // 并发的只读工具数上限，默认 5
    int consecutiveErrorLimit // 连续错误上限，默认 3
) {}
```

### LoopState（新增）

```java
enum LoopState { RUNNING, CANCELLED, COMPLETED, MAX_ITERATIONS, ERROR }
```

### StreamCollector（新增）

```java
class StreamCollector implements StreamCallback {
    StreamCollector(Consumer<String> realtimeSink);

    void onToken(String token);       // 同时推实时 + 累积
    void onToolCall(ToolCall tc);     // 累积完整 tool_use
    void onUsage(Usage usage);        // 记录 token 用量

    String getFullText();
    List<ToolCall> getToolCalls();
    Usage getUsage();
}

record Usage(int inputTokens, int outputTokens) {}
```

### ToolExecutor（新增）

```java
class ToolExecutor {
    ToolExecutor(ToolRegistry registry, int maxParallelReads);
    List<ToolResult> execute(List<ToolCall> calls);  // 保序分批并发
}
```

### LlmProvider 增强（修改）

```java
interface LlmProvider {
    void stream(List<ChatMessage> messages, ToolRegistry tools, StreamCallback callback);
    Usage lastUsage();  // ★ 新增
}
```

### StreamCallback 增强（修改）

```java
interface StreamCallback {
    void onToken(String token);
    default void onToolCall(ToolCall toolCall) {}
    default void onUsage(Usage usage) {}  // ★ 新增
}
```

### ChatMessage 扩展（修改）

```java
record ChatMessage(
    String role,
    String content,
    String toolUseId,    // tool_result 时非 null
    String toolName      // tool_result 时非 null
) {
    ChatMessage(String role, String content) {
        this(role, content, null, null);
    }
    static ChatMessage toolResult(String toolUseId, String toolName, String content) {
        return new ChatMessage("user", content, toolUseId, toolName);
    }
}
```

## 模块设计

### 模块 A：core/agent/AgentLoop — ReAct 循环引擎

**职责：** 驱动多轮 LLM ↔ Tool 交互，管理循环状态和停止条件

**对外接口：**
- `run(List<ChatMessage> history, LlmProvider provider, ToolRegistry tools)`
- `cancel()` — 外部取消（Ctrl+C 触发）

**核心流程：**
1. 检查停止条件（取消/上限/错误）
2. 决定本轮注入哪些工具（Plan Mode 只注入只读）
3. 创建 StreamCollector → 调 LLM
4. 获取结果 text + toolCalls
5. 无 toolCalls → COMPLETED
6. ToolExecutor 保序分批并发执行
7. 回灌结果到 history
8. 错误计数、迭代计数 → 回到步骤1

**停止条件：**
- 模型完成（无 tool_use）
- 迭代上限
- 用户取消
- 未知工具
- 连续错误 3 次
- 流出错

### 模块 B：StreamCollector — 流式双路收集

**职责：** 同时走实时推送和累积拼接两路

- 实时通道：每收到 token 立刻 push 给 Consumer<String>
- 累积通道：StringBuilder 攒完整文本 + List<ToolCall> 累积 tool_use
- Usage 提取：onUsage() 记录，getUsage() 获取

### 模块 C：ToolExecutor — 保序分批并发执行

**职责：** 接收 tool_use 列表，按工具类型分批，只读并发、写入串行

**算法：**
```
扫描 toolCalls 列表:
  连续的只读 → 合并为一批 → ExecutorService.invokeAll() 并发执行
  遇到写工具 → flush 前一批 → 单独执行 → 开始下一批只读
结果按原始顺序排列返回
```

### 模块 D：Plan Mode

Repl 维护 mode 字段（PLAN / DO），在传给 AgentLoop 前决定 activeTools：
- `:plan` → mode=PLAN → 只注入只读工具 + 计划态系统提示
- `:do` → mode=DO → 注入全工具 + 执行态系统提示

### 模块 E：异步事件管道

- BlockingQueue<AgentEvent> 容量 1000
- AgentLoop 线程（Producer）put 事件
- Repl 主线程（Consumer）take 事件 → 渲染到界面

## 模块交互

```
用户输入
  │
  ▼
Repl.processMessage()
  ├─ session.addMessage(user)
  ├─ AgentLoop.run(history, provider, activeTools)
  │     │
  │     └─ 后台线程循环:
  │          Round 1: LLM → tool_use → execute → 回灌 → 继续
  │          Round 2: LLM → text → DONE
  │          (每步 push AgentEvent 到队列)
  │
  └─ Repl 消费事件 → 界面显示 → session.addMessage(assistant)
```

## 文件组织

```
src/main/java/com/mewcode/
├── cli/
│   ├── Main.java                  — 不变
│   └── Repl.java                  — ★ 重构
├── core/
│   ├── App.java                   — ★ 修改
│   └── agent/
│       ├── AgentLoop.java         — ★ 新增
│       ├── AgentEvent.java        — ★ 新增
│       ├── LoopConfig.java        — ★ 新增
│       └── LoopState.java         — ★ 新增
├── llm/
│   ├── ChatMessage.java           — ★ 扩展
│   ├── LlmProvider.java           — ★ lastUsage()
│   ├── StreamCallback.java        — ★ onUsage()
│   ├── StreamCollector.java       — ★ 新增
│   ├── AnthropicProvider.java     — ★ 解析 usage
│   └── OpenAiProvider.java        — ★ 解析 usage
├── tools/
│   └── ToolExecutor.java          — ★ 新增
└── ...

src/test/java/com/mewcode/
├── core/agent/
│   ├── AgentLoopTest.java
│   └── EventQueueTest.java
├── llm/
│   └── StreamCollectorTest.java
└── tools/
    └── ToolExecutorTest.java
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 事件总线 | `BlockingQueue<AgentEvent>` | JDK 内置，阻塞语义天然匹配 Producer/Consumer |
| AgentLoop 线程模型 | 单后台线程 + Repl 主线程消费 | 简单清晰，避免多消费者竞态 |
| StreamCollector | 实现 StreamCallback 接口 | 复用现有 Provider 接口，零改动接入 |
| 并发工具执行 | `ExecutorService.invokeAll` | 内置超时 + 异常传播，无需第三方 |
| Plan Mode 实现 | Repl 控制 activeTools + 注入系统消息 | AgentLoop 不感知模式，职责单一 |
| 取消机制 | `volatile` 标志位 | 轻量，无锁 |
| 用量解析 | SSE 中 `usage` 事件 | 协议原生支持，精确 |
