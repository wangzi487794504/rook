---
title: "task"
author: wangzijie
date: 2026-08-05
---

# MewCode 第3章 - Agent Loop Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `core/agent/AgentEvent.java` | 事件类型 + 载体 record |
| 新建 | `core/agent/LoopState.java` | 循环状态枚举 |
| 新建 | `core/agent/LoopConfig.java` | 循环配置 record |
| 新建 | `core/agent/AgentLoop.java` | ReAct 循环引擎 |
| 新建 | `llm/StreamCollector.java` | 流式双路收集器 |
| 新建 | `llm/Usage.java` | Token 用量 record |
| 新建 | `tools/ToolExecutor.java` | 保序分批并发执行器 |
| 修改 | `llm/ChatMessage.java` | 扩展 toolResult 字段 |
| 修改 | `llm/StreamCallback.java` | 新增 onUsage |
| 修改 | `llm/LlmProvider.java` | 新增 lastUsage |
| 修改 | `llm/AnthropicProvider.java` | 解析 usage 事件 |
| 修改 | `llm/OpenAiProvider.java` | 解析 usage 事件 |
| 修改 | `cli/Repl.java` | AgentLoop 集成 + Plan Mode |
| 修改 | `core/App.java` | 创建配置对象 |
| 新建 | `test/core/agent/EventQueueTest.java` | 事件队列测试 |
| 新建 | `test/llm/StreamCollectorTest.java` | 收集器测试 |
| 新建 | `test/tools/ToolExecutorTest.java` | 执行器测试 |
| 新建 | `test/core/agent/AgentLoopTest.java` | 循环引擎测试 |

## T1: 创建基础类型 AgentEvent + LoopState + LoopConfig + Usage

**文件：**
- `core/agent/AgentEvent.java`
- `core/agent/LoopState.java`
- `core/agent/LoopConfig.java`
- `llm/Usage.java`

**依赖：** 无
**步骤：**
1. `LoopState` 枚举：RUNNING、CANCELLED、COMPLETED、MAX_ITERATIONS、ERROR
2. `LoopConfig` record：maxIterations(25)、maxParallelReads(5)、consecutiveErrorLimit(3)
3. `AgentEventType` 枚举 + `AgentEvent` record 含 type/text/toolCall/toolResult/iteration/totalIterations
4. `Usage` record：inputTokens、outputTokens

**验证：** `mvnw compile` 编译通过

## T2: 扩展 ChatMessage + StreamCallback + LlmProvider

**文件：**
- `llm/ChatMessage.java`
- `llm/StreamCallback.java`
- `llm/LlmProvider.java`

**依赖：** T1
**步骤：**
1. ChatMessage 增加 toolUseId/toolName 字段，添加向后兼容构造函数和静态工厂 toolResult()
2. StreamCallback 增加 `default void onUsage(Usage usage) {}`
3. LlmProvider 增加 `Usage lastUsage()` 方法，默认返回 null

**验证：** `mvnw compile` 编译通过

## T3: 创建 StreamCollector

**文件：** `llm/StreamCollector.java`
**依赖：** T1, T2
**步骤：**
1. 实现 StreamCallback 接口
2. 构造函数接收 `Consumer<String> realtimeSink`
3. `onToken()`：realtimeSink.accept(token) + fullText.append(token)
4. `onToolCall()`：累积到 `List<ToolCall>` toolCalls 列表
5. `onUsage()`：记录 Usage
6. 提供 getFullText()、getToolCalls()、getUsage()

**验证：** `mvnw compile` 编译通过

## T4: 修改 AnthropicProvider 解析 usage 事件

**文件：** `llm/AnthropicProvider.java`
**依赖：** T2
**步骤：**
1. 新增字段 `Usage lastUsage`
2. SSE 解析中识别 `message_delta` 类型事件
3. `stream()` 结束时 `callback.onUsage(usage)`
4. 实现 `lastUsage()` 返回保存的值

**验证：** `mvnw compile` 编译通过

## T5: 修改 OpenAiProvider 解析 usage 事件

**文件：** `llm/OpenAiProvider.java`
**依赖：** T2
**步骤：**
1. 新增字段 `Usage lastUsage`
2. SSE 解析中识别 `usage` 字段
3. `stream()` 结束时 `callback.onUsage(usage)`
4. 实现 `lastUsage()` 返回保存的值

**验证：** `mvnw compile` 编译通过

## T6: 创建 ToolExecutor

**文件：** `tools/ToolExecutor.java`
**依赖：** T1
**步骤：**
1. 构造：接收 ToolRegistry + maxParallelReads
2. `execute(List<ToolCall>)` 实现分批算法：
   - 遍历 toolCalls，连续只读并入并发批
   - 遇写/exec flush 前批，再单步执行写
   - 末尾 flush 剩余批
3. 并发批用 `ExecutorService.invokeAll()` 执行
4. 结果按原始顺序排列返回

**验证：** `mvnw compile` 编译通过

## T7: 创建 AgentLoop

**文件：** `core/agent/AgentLoop.java`
**依赖：** T1-T6
**步骤：**
1. 构造：接收 LoopConfig
2. `run(List<ChatMessage>, LlmProvider, ToolRegistry)`：
   - state=RUNNING, iteration=1, consecutiveErrors=0
   - while RUNNING: checkStop → StreamCollector → provider.stream → ToolExecutor → 回灌 → push 事件
3. 每步 push AgentEvent 到 BlockingQueue
4. `cancel()`：设 state=CANCELLED
5. 停止条件检查：取消/上限/未知工具/连续错误/流出错
6. 取消时补「已取消」tool_result

**验证：** `mvnw compile` 编译通过

## T8: 重构 Repl（AgentLoop + Plan Mode）

**文件：** `cli/Repl.java`
**依赖：** T7
**步骤：**
1. processMessage() 改为：
   - AgentLoop 后台线程执行
   - 主线程消费 BlockingQueue 事件
   - TEXT → 打印，PROGRESS → 轮次，DONE → 退出消费循���
2. Ctrl+C（流式态）→ agentLoop.cancel()
3. `:plan` → mode=PLAN，只注入只读工具 + 计划态系统提示
4. `:do` → mode=DO，全工具 + 执行态系统提示
5. 收集最终 fullText → session.addMessage(assistant)

**验证：** `mvnw compile` 编译通过

## T9: 修改 App 创建配置

**文件：** `core/App.java`
**依赖：** T7
**步骤：**
1. 创建 LoopConfig 实例，传给 Repl

**验证：** `mvnw compile` 编译通过

## T10: 事件队列测试

**文件：** `test/core/agent/EventQueueTest.java`
**依赖：** T1
**验证：** `mvnw test -Dtest=EventQueueTest`

## T11: StreamCollector 测试

**文件：** `test/llm/StreamCollectorTest.java`
**依赖：** T3
**验证：** `mvnw test -Dtest=StreamCollectorTest`

## T12: ToolExecutor 测试

**文件：** `test/tools/ToolExecutorTest.java`
**依赖：** T6
**验证：** `mvnw test -Dtest=ToolExecutorTest`

## T13: AgentLoop 测试

**文件：** `test/core/agent/AgentLoopTest.java`
**依赖：** T7
**验证：** `mvnw test -Dtest=AgentLoopTest`

## 执行顺序

```
T1 ──→ T2 ──→ T3 ──→ T6 ──→ T7 ──→ T8 ──→ T9
  │           │                    │
  │           ├──→ T4 ────────────┤
  │           └──→ T5 ────────────┘
  │
  └──→ T10 (可与 T2-T7 并行)

T3 ──→ T11 (可与 T6-T9 并行)
T6 ──→ T12 (可与 T7-T9 并行)
T7 ──→ T13 (可与 T8-T9 并行)
```
