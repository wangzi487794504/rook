---
title: "task"
author: wangzijie
date: 2026-08-05
---

# MewCode 第2章 - 工具系统 Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `tools/Tool.java` | 工具接口 |
| 新建 | `tools/ToolResult.java` | 结果 record |
| 新建 | `tools/ToolCall.java` | 工具调用 record |
| 新建 | `tools/ToolRegistry.java` | 注册中心 |
| 新建 | `tools/impl/ReadFileTool.java` | 读文件 |
| 新建 | `tools/impl/WriteFileTool.java` | 写文件 |
| 新建 | `tools/impl/EditFileTool.java` | 改文件 |
| 新建 | `tools/impl/ExecCommandTool.java` | 执行命令 |
| 新建 | `tools/impl/GlobTool.java` | glob 搜索 |
| 新建 | `tools/impl/GrepTool.java` | grep 搜索 |
| 修改 | `llm/StreamCallback.java` | 新增 onToolCall |
| 修改 | `llm/LlmProvider.java` | stream() 加 ToolRegistry 参数 |
| 修改 | `llm/AnthropicProvider.java` | tools 请求 + tool_use 解析 |
| 修改 | `llm/OpenAiProvider.java` | tools 请求 + tool_calls 解析 |
| 修改 | `core/App.java` | 创建 ToolRegistry 并注入 |
| 修改 | `cli/Repl.java` | 工具调度链路 |
| 新建 | `test/.../tools/ToolRegistryTest.java` | 注册中心测试 |
| 新建 | `test/.../tools/impl/*Test.java` | 6个工具测试 |

## T1: 创建 Tool 接口 + ToolResult + ToolCall

**文件：**
- `tools/Tool.java`
- `tools/ToolResult.java`
- `tools/ToolCall.java`

**依赖：** 无
**步骤：**
1. 创建 `Tool` 接口：`name()`、`description()`、`inputSchema()`、`execute(Map)`
2. 创建 `ToolResult` record：`success` (boolean) + `content` (String)，静态工厂 ok()/error()
3. 创建 `ToolCall` record：`id`、`name`、`input`

**验证：** `mvnw compile` 编译通过

## T2: 创建 ToolRegistry

**文件：** `tools/ToolRegistry.java`
**依赖：** T1
**步骤：**
1. 内部用 `LinkedHashMap<String, Tool>` 存储
2. `register(Tool)` — 注册工具
3. `get(String name)` — 返回 `Optional<Tool>`
4. `toToolDefinitions()` — 遍历所有工具，构造 List\<Map\>
5. `names()` — 返回所有注册的工具名称集合

**验证：** `mvnw compile` 编译通过

## T3: 修改 StreamCallback + LlmProvider 接口

**文件：**
- `llm/StreamCallback.java`
- `llm/LlmProvider.java`

**依赖：** T1
**步骤：**
1. `StreamCallback` 新增 `default void onToolCall(ToolCall toolCall) {}`
2. `LlmProvider.stream()` 方法签名增加 `ToolRegistry tools` 参数
3. 同步更新 `LlmProvider.complete()` 的 default 实现

**验证：** `mvnw compile` 编译通过

## T4: 实现 ReadFileTool

**文件：** `tools/impl/ReadFileTool.java`
**依赖：** T1
**步骤：**
1. 参数 schema：`filePath`（必填，String）、`offset`（选填，int）、`limit`（选填，int）
2. 实现：`Files.readString()` 读文件，有 offset/limit 则按行截取
3. 文件不存在返回 error
4. 内容超 100KB 截断，末尾标注
5. 用 UTF-8 读取

**验证：** `mvnw compile` 编译通过

## T5: 实现 WriteFileTool

**文件：** `tools/impl/WriteFileTool.java`
**依赖：** T1
**步骤：**
1. 参数 schema：`filePath`（必填）、`content`（必填）
2. 实现：先写 `.tmp` 临时文件，再 `Files.move(tmp, target, ATOMIC_MOVE)`
3. 父目录不存在时 `Files.createDirectories()`
4. 权限问题返回 error

**验证：** `mvnw compile` 编译通过

## T6: 实现 EditFileTool

**文件：** `tools/impl/EditFileTool.java`
**依赖：** T1
**步骤：**
1. 参数 schema：`filePath`（必填）、`oldString`（必填）、`newString`（必填）
2. 实现：
   - 读取文件全文
   - 搜索 oldString 出现次数
   - 0次 → error("未找到匹配的原文片段")
   - N次(N>1) → error("匹配到 N 处，请提供更精确的上下文")
   - 1次 → 替换，通过 WriteFileTool 写回
3. 支持多行替换

**验证：** `mvnw compile` 编译通过

## T7: 实现 ExecCommandTool

**文件：** `tools/impl/ExecCommandTool.java`
**依赖：** T1
**步骤：**
1. 参数 schema：`command`（必填）、`workingDir`（选填）
2. Windows 用 `cmd /c`，其他用 `sh -c`
3. 超时 30 秒，`Process.waitFor(30, SECONDS)`
4. 超时 → `destroyForcibly()` → 返回 error
5. 正常 → 返回 stdout+stderr 合并内容，超 100KB 截断

**验证：** `mvnw compile` 编译通过

## T8: 实现 GlobTool

**文件：** `tools/impl/GlobTool.java`
**依赖：** T1
**步骤：**
1. 参数 schema：`pattern`（必填）、`path`（选填，默认 "."）
2. 用 `PathMatcher("glob:" + pattern)` + `Files.find()` 遍历
3. 返回匹配文件路径列表（相对路径），每行一个
4. 结果上限 500 条，超出截断
5. 目录不存在返回 error

**验证：** `mvnw compile` 编译通过

## T9: 实现 GrepTool

**文件：** `tools/impl/GrepTool.java`
**依赖：** T1
**步骤：**
1. 参数 schema：`pattern`（必填）、`path`（选填，默认 "."）、`include`（选填，文件名过滤 glob）、`ignoreCase`（选填，默认 true）
2. 逐文件逐行正则搜索
3. 输出格式：`文件:行号:内容`
4. 结果上限 200 条，超出截断
5. `include` 参数用 `PathMatcher` 过滤文件名

**验证：** `mvnw compile` 编译通过

## T10: 修改 AnthropicProvider 支持 tools

**文件：** `llm/AnthropicProvider.java`
**依赖：** T3, T2
**步骤：**
1. `stream()` 方法内构建请求体时，若 `tools` 非空，追加 `"tools"` 数组
2. SSE 解析新增处理 `content_block_start`（tool_use）、`content_block_delta`（input_json_delta）、`content_block_stop`
3. 拼接完整 input JSON 后回调 `callback.onToolCall()`

**验证：** `mvnw compile` 编译通过

## T11: 修改 OpenAiProvider 支持 tools

**文件：** `llm/OpenAiProvider.java`
**依赖：** T3, T2
**步骤：**
1. `stream()` 方法内构建请求体时，若 `tools` 非空，追加 `"tools"` 数组（OpenAI 格式）
2. SSE 解析处理 `choices[0].delta.tool_calls`，按 index 归并拼接
3. 收集完整后回调 `callback.onToolCall()`

**验证：** `mvnw compile` 编译通过

## T12: 修改 App 创建 ToolRegistry

**文件：** `core/App.java`
**依赖：** T2, T4-T9
**步骤：**
1. 在 `run()` 方法中创建 `ToolRegistry` 实例
2. 注册六个工具
3. 将 Registry 传给 Repl 构造函数

**验证：** `mvnw compile` 编译通过

## T13: 修改 Repl 实现工具调度

**文件：** `cli/Repl.java`
**依赖：** T3, T2, T12
**步骤：**
1. 构造方法接收 `ToolRegistry`
2. `processMessage()` 新流程：
   - 创建回调，`onToolCall()` 中标记并记录 ToolCall
   - 调用 `provider.stream(messages, toolRegistry, callback)`
   - 若收到 toolCall → 打印工具调用信息 → 执行工具 → 打印结果摘要 → 构造 tool_result 消息追加 → 再次调用 stream()
   - 若只有文本 → 逐字打印
3. tool_result 消息构造

**验证：** `mvnw compile` 编译通过

## T14-T20: 测试

**文件：**
- `ToolRegistryTest.java` — 注册/查找/API 格式验证
- `ReadFileToolTest.java` — 读存在/不存在/大文件截断
- `WriteFileToolTest.java` — 写/覆盖/权限
- `EditFileToolTest.java` — 唯一匹配/0匹配/多匹配
- `ExecCommandToolTest.java` — 正常执行/超时
- `GlobToolTest.java` — 基本匹配/无匹配
- `GrepToolTest.java` — 基本搜索/大小写/过滤

**依赖：** 对应各工具实现完成后
**验证：** `mvnw test` 全部通过

## 执行顺序

```
T1 ──→ T2 ──→ T12 ──────────────────┐
  │                                   │
  ├─→ T3 ──→ T10 ──→ T11             │
  │           │       │               │
  │           └───┬───┘               │
  │               ↓                   │
  │             (并行)                 │
  │                                   │
  ├─→ T4 ──→ T5 ──→ T6 ──→ T7 ──→ T8 ──→ T9
  │    (T4-T9 可并行)
  │                                   │
  └───────────────────────────────────┼──→ T13
                                      │
T2 ──→ T14-T20 (测试，实现完成后并行)  │
                                      ↓
                                 全部编译通过 → 集成测试
```
