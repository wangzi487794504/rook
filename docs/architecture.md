# MewCode 架构文档

## 一、项目概览

MewCode 是一个终端 AI 编程助手（类似 Claude Code），用 Java 17 实现。用户通过命令行 REPL 与之交互，它可以调用 LLM 生成回复、执行文件操作、运行 shell 命令、搜索代码，还能接入外部 MCP Server 扩展工具。

```
用户输入 → REPL 界面 → AgentLoop 引擎 → LLM Provider → AI 回复
                           ↓
                      ToolExecutor（权限检查 → 工具执行）
                           ↓
                  内置工具 + MCP 外部工具
```

### 技术栈

| 组件 | 技术 |
|------|------|
| 语言 | Java 17 |
| 构建 | Maven |
| JSON | Jackson |
| YAML | SnakeYAML |
| HTTP | java.net.http.HttpClient |
| CLI | JLine 3 |
| LLM 协议 | Anthropic Messages API / OpenAI Chat Completions |
| 外部工具 | MCP (Model Context Protocol) JSON-RPC 2.0 |

---

## 二、包结构

```
com.mewcode/
├── cli/              ← 命令行入口和 REPL 交互
├── core/             ← 核心引擎
│   └── agent/        ←   Agent Loop 子系统
├── config/           ← LLM 配置管理
├── llm/              ← LLM 调用与 Streaming
├── tools/            ← 工具系统
│   └── impl/         ←   六个内置工具
├── session/          ← 会话管理
├── prompt/           ← System Prompt 构建
│   └── modules/      ←   Prompt 模块
├── permission/       ← 五层权限系统
└── mcp/              ← MCP 客户端
```

---

## 三、各模块详解

### 3.1 cli/ — 命令行入口

```
Main.java         启动入口：解析 --config 参数 → 调用 App.run()
Repl.java         交互循环：JLine3 终端输入 → 命令分发 / 对话处理
                  ─ 命令：:help :exit :switch :model :clear
                          :tools :plan :do :mode :mcp
                  ─ 对话：processMessage → AgentLoop.run → 事件消费
                  ─ 全角冒号兼容、Ctrl+C 处理
```

**启动流程：**
```
Main → App.run(configPath)
         ├─ ConfigLoader.load()
         ├─ ProviderFactory.create()
         ├─ ToolRegistry.register(6个内置工具)
         ├─ McpManager.discover() ← 发现外部 MCP 工具
         ├─ Session.load()
         ├─ PermissionEngine 创建
         ├─ PromptBuilder 创建（10个模块）
         └─ Repl.start()
```

**对话流程：**
```
Repl.processMessage(input)
  ├─ session.addMessage(user消息)
  ├─ agentLoop.run(history, provider, activeTools, lineReader)
  │     └─ 后台线程：多轮 LLM↔Tool 循环
  │         每步推送 AgentEvent 到 BlockingQueue
  ├─ consumeEvents(queue) → 界面渲染
  └─ syncSessionFromHistory()
```

---

### 3.2 core/ + core/agent/ — 核心引擎

#### App.java
启动协调器，负责依赖注入：
```
new App().run(configPath)
  1. ConfigLoader.load()       → AppConfig
  2. ProviderFactory.create()  → Map<String, LlmProvider>
  2a. ToolRegistry + 6 tools + MCP discover
  2b. PermissionEngine(sandbox, ruleEngine, human)
  2c. PromptBuilder(10 modules)
  3. Session.load()
  4. new Repl(...) → repl.start()
```

#### AgentLoop.java — ReAct 循环引擎
```
run(history, provider, tools, lineReader):
  ┌─ while(state == RUNNING):
  │    ├─ 检查停止条件（上限/取消/未知工具/连续错误）
  │    ├─ PromptBuilder.build(ctx) → system prompt
  │    ├─ SupplementMessage 补充注入
  │    ├─ StreamCollector → provider.stream()
  │    ├─ 无 tool_use → COMPLETED, break
  │    ├─ ChatMessage.assistantWithTools() 写入历史
  │    ├─ ToolExecutor.execute(toolCalls)  → 工具执行
  │    ├─ 结果回灌到历史
  │    └─ 错误计数/迭代计数
  └─ push DONE 事件 → 结束
```

停止条件：模型完成 | 迭代上限(25) | 用户取消 | 未知工具 | 连续错误(3) | 流出错

#### AgentEvent.java + AgentEventType.java
事件体系——解耦 AgentLoop 和 UI：
- TEXT: 流式文本片段 → 实时打印
- TOOL_CALL: 工具调用 → 显示调用信息
- TOOL_RESULT: 工具结果 → 显示成功/失败
- TOKEN_USAGE: Token 用量 → 统计
- PROGRESS: 轮次进度 → "第 3/25 轮"
- ERROR: 异常 → 停止提示
- DONE: 正常结束 → 退出消费循环

#### LoopConfig.java / LoopState.java
配置(maxIterations, maxParallelReads, consecutiveErrorLimit) 和状态枚举

---

### 3.3 config/ — LLM 配置

```
ProviderConfig.java   record: name, protocol, model, baseUrl, apiKey
AppConfig.java        record: providers(List), defaultProvider
ConfigLoader.java     YAML 读取 + 字段校验
                     ─ 校验 protocol 为 anthropic/openai
                     ─ 校验 defaultProvider 存在
```

配置格式 config.yaml：
```yaml
default_provider: deepseek
providers:
  - name: deepseek
    protocol: anthropic
    model: deepseek-v4-pro
    base_url: https://api.deepseek.com/anthropic
    api_key: sk-xxxx
```

---

### 3.4 llm/ — LLM 调用层

```
ChatMessage.java    消息体: role, content, toolUseId, toolName, toolCalls
                    ─ 普通文本: new ChatMessage("user", "hello")
                    ─ assistant+工具: ChatMessage.assistantWithTools(text, calls)
                    ─ tool_result: ChatMessage.toolResult(id, name, content)

StreamCallback.java 流式回调接口: onToken() / onToolCall() / onUsage()
StreamCollector.java 双路收集器: 实时推送 + 累积拼接
                     ─ 构造函数注入 realtimeSink(Consumer<String>)
                     ─ onToken → sink.accept() + fullText.append()
                     ─ 累积 toolCalls[] + Usage

Usage.java          token 用量: inputTokens, outputTokens,
                    cacheReadInputTokens, cacheCreationInputTokens

LlmProvider.java    抽象接口
                    ─ stream(msgs, tools, callback)
                    ─ lastUsage()

AnthropicProvider   Anthropic Messages API 实现
                    ─ POST {base_url}/messages
                    ─ 请求头: x-api-key, anthropic-version: 2023-06-01
                    ─ tools 定义: {name, description, input_schema}
                    ─ SSE 解析: content_block_start → tool_use 检测
                               content_block_delta → text_delta / input_json_delta
                               message_delta → usage 提取
                               message_start → 缓存命中信息
                    ─ 消息转换: tool_result 合并为单条 content block 数组
                               assistant+tool_use 展开为 content blocks

OpenAiProvider      OpenAI Chat Completions API 实现
                    ─ POST {base_url}/chat/completions
                    ─ tools 定义: {type: function, function: {name, parameters}}
                    ─ SSE 解析: delta.content → 文本
                               delta.tool_calls[] → 按 index 归并
                               usage 字段 → 用量

ProviderFactory     根据 protocol 字段创建 AnthropicProvider / OpenAiProvider
LlmException        LLM 调用异常，含 statusCode
```

**关键设计：消息转换**
- Anthropic: tool_result 连续多条合并为一条 `user` 消息的 content 数组
- OpenAI: tool_result 逐条转为 `tool` 角色消息
- assistant+tool_use → content blocks 数组 (Anthropic) / tool_calls 数组 (OpenAI)

---

### 3.5 tools/ + tools/impl/ — 工具系统

```
Tool.java          工具抽象接口: name() / description() / inputSchema() / execute(params)

ToolCall.java      LLM 返回的工具调用: id, name, input(Map)
ToolResult.java    执行结果: success(boolean), content(String)
                   ─ ToolResult.ok("结果") / ToolResult.error("原因")
                   ─ 权限拒绝: content = "PERMISSION_DENIED: 原因"

ToolRegistry.java  工具注册中心: register() / get() / unregister() / toToolDefinitions()
                   内部 LinkedHashMap<String, Tool>

ToolExecutor.java  执行器: 保序分批并发 + 权限检查
                   ─ 扫描工具列表，连续只读→并发批，写入→串行
                   ─ executeOne() 前调用 PermissionEngine.check()
                   ─ 注入 PermissionEngine + LineReader(人在回路)

impl/
  ReadFileTool     读文件: Files.readString, 支持 offset/limit, 100KB 截断
  WriteFileTool    写文件: .tmp + ATOMIC_MOVE 原子写入，自动创建父目录
  EditFileTool     改文件: 精确字符串匹配替换，0/多次→报错，唯一→替换
  ExecCommandTool  执行命令: ProcessBuilder, 30s超时, stdout/stderr合并
  GlobTool         文件搜索: PathMatcher("glob:") + Files.walk
  GrepTool         代码搜索: 逐行正则匹配，支持 include 文件过滤
```

**执行器分批算法：**
```
输入 [read_file, read_file, edit_file, glob]
  ↓ 扫描
  批1并发: [read_file, read_file]  (连续的只读)
  串行:    [edit_file]            (遇到写操作)
  批2并发: [glob]                 (剩余只读)
输出 [结果1, 结果2, 结果3, 结果4]   ← 保持原始顺序
```

---

### 3.6 session/ — 会话管理

```
Session.java       消息历史: List<ChatMessage>
                   ─ addMessage() / getMessages() / clear()
                   ─ save(Path) → Jackson JSON 序列化
                   ─ load(Path) → 反序列化 / 文件不存在返回空 Session
                   持久化位置: ~/.mewcode/sessions/default.json
```

---

### 3.7 prompt/ + prompt/modules/ — System Prompt

```
PromptContext.java     渲染上下文: planMode, iteration, workingDir, os, activeSkills
PromptModule.java      模块接口: name() + priority() + render(ctx)
PromptBuilder.java     装配器: 按优先级排序 → 逐模块渲染 → "\n\n" 拼接
SupplementMessage.java 补充消息: [Supplement] 标签 user 消息
                       ─ 不污染缓存通道
                       ─ 不被模型当用户输入回复

modules/
  固定模块 (priority 1-7):
    IdentityModule(1)            身份: "你是 MewCode 终端 AI 编程助手..."
    SystemConstraintsModule(2)   系统约束: 不编造/不跳过/错误处理
    TaskModeModule(3)            任务模式: planMode? "计划模式" : "正常模式"
    ActionExecutionModule(4)     动作执行: 工具调用流程说明
    ToolUsageModule(5)           工具使用: 通用约定 + 双重强化规则
    ToneStyleModule(6)           语气风格: 中文/简洁/代码标注
    TextOutputModule(7)          文本输出: 代码块规范

  可选模块 (priority 71-100):
    CustomInstructionsModule(80)  自定义指令（外部设置）
    ActiveSkillsModule(85)        已激活 Skill 列表
    LongTermMemoryModule(90)      长期记忆（外部设置）
```

**两通道设计：**
```
缓存通道: messages[0] = system(七个固定模块 + 工具描述)
          → 每次请求相同，Anthropic 可缓存
非缓存:   messages末尾 = user([Supplement] 环境信息/模式提示)
          → 每次可能不同，不缓存
```

---

### 3.8 permission/ — 五层权限系统

```
PermissionVerdict   枚举: ALLOW / DENY / ASK
PermissionResult    record: verdict + reason + layer + 静态工厂
PermissionMode      enum 四档: DEFAULT / ACCEPT_EDITS / PLAN / BYPASS
                    每档定义读/写/执行三类工具的兜底裁决

Blacklist           第1层: 内置正则，拦高危命令，不可绕过
PathSandbox         第2层: 路径前缀检查，toRealPath() 防 symlink 逃逸
RuleEngine          第3层: 三层 YAML 规则合并(local>project>user)
                    glob 匹配工具参数，allow/deny 两条结果
PermissionEngine    五层串联调度: 逐层check()，短路返回
HumanInTheLoop      第5层: JLine终端确认界面
                    ─ 1=允许本次 / 2=永久允许 / 3=拒绝本次
                    ─ 永久允许 → 追加 YAML 行到 rules.local.yaml

RuleConfig          record: toolName, pattern, allow, source
ToolCategory        enum: READ / WRITE / EXEC
```

**五层检查流程：**
```
toolCall → 黑名单(Deny→短路) → 沙箱(Deny→短路) → 规则引擎(命中→短路)
                                                      ↓ 未命中
                                                 模式兜底(Allow→放行, Ask→人在回路)
```

---

### 3.9 mcp/ — MCP 客户端

```
JsonRpc.java       JSON-RPC 2.0 消息构造/解析
                   ─ buildRequest(id, method, params)
                   ─ parseResponse(json) → JsonRpcResponse(ok/error)
                   ─ AtomicInteger ID 生成

McpConfig.java     配置加载 + ${VAR} 展开 + 两层合并
                   ─ 用户级: ~/.mewcode/mcp.yaml
                   ─ 项目级: .mewcode/mcp.yaml

McpTransport.java  传输接口: send(jsonRpc) → response, close()
StdioTransport     子进程管道: ProcessBuilder → stdin/stdout 行协议
HttpTransport      HTTP POST + SSE: HttpClient → POST → 解析 data: 行或纯 JSON

McpSession.java    单 Server 会话（三阶段）
                   ─ initialize() → 握手 + 列工具
                   ─ callTool(name, params) → 调用
                   ─ 内部 BlockingQueue 串行化: 每 Server 单消费线程
                   ─ 与 transport.send() 同步 => 请求/响应按 id 匹配

McpToolAdapter     远端→本地适配: implements Tool
                   ─ name() = "mcp_" + serverName + "_" + toolName
                   ─ execute() → session.callTool()

McpManager.java    总控: 加载配置 → 并行初始化 → 注册到 ToolRegistry
                   ─ reload() → 关闭旧连接 → 移除旧工具 → 重新 discover
```

**MCP 配置格式 (mcp.yaml)：**
```yaml
servers:
  everything:
    type: stdio
    command: npx
    args: ["-y", "@modelcontextprotocol/server-everything"]
  remote:
    type: http
    url: https://mcp.example.com/mcp
    headers:
      Authorization: "Bearer ${API_KEY}"
```

---

## 四、数据流全景

### 一次完整的用户请求处理

```
用户输入 "帮我读 pom.xml"
        │
        ▼
Repl.processMessage()
  ┌─────────────────────────────────────────────────────┐
  │ 1. session.addMessage(user, "帮我读 pom.xml")      │
  │ 2. agentLoop.run(history, provider, tools, reader) │
  └─────────────────────────────────────────────────────┘
        │
        ▼ 后台线程
  ┌─────────────────────────────────────────────────────┐
  │ AgentLoop (Round 1)                                │
  │                                                     │
  │ PromptBuilder.build(ctx)                            │
  │   → "## 身份\n你是 MewCode...\n\n## 工具使用\n..." │
  │ history.add(0, system消息)                          │
  │                                                     │
  │ StreamCollector collector(token → eventQueue)       │
  │ provider.stream(history, tools, collector)          │
  │   → POST https://api.deepseek.com/anthropic/messages│
  │   → SSE: {"type":"content_block_delta",             │
  │            "delta":{"type":"text_delta",            │
  │            "text":"我来"}}                           │
  │   → collector.onToken("我来")                       │
  │   → eventQueue.put(AgentEvent.text("我来"))         │
  │   ...持续接收 token...                              │
  │   → SSE: {"type":"content_block_start",             │
  │            "content_block":{"type":"tool_use",      │
  │            "id":"call_01","name":"read_file"}}      │
  │   → 拼接 input_json_delta                           │
  │   → collector.onToolCall(read_file, {filePath:...}) │
  │                                                     │
  │ 无新 token → collector.getToolCalls() ≠ empty       │
  │ history.add(assistant+tool_use)                     │
  │ ToolExecutor.execute([read_file])                   │
  │   → PermissionEngine.check()                        │
  │     → 黑名单: ALLOW                                 │
  │     → 沙箱: pom.xml 在项目内 → ALLOW                │
  │     → 规则引擎: 未命中 → 穿透                       │
  │     → 模式兜底: READ=ALLOW → 执行                   │
  │   → ReadFileTool.execute({filePath:"pom.xml"})      │
  │   → ToolResult.ok("<?xml version=...")              │
  │ history.add(tool_result)                            │
  │                                                     │
  │ Round 2: 继续循环                                   │
  │ provider.stream(history_with_result, tools, ...)    │
  │   → 模型看到文件内容，生成总结文本                   │
  │   → collector.getToolCalls() = empty                │
  │ history.add(assistant, "你的 pom.xml 包含...")      │
  │ state = COMPLETED                                   │
  │ eventQueue.put(DONE)                                │
  └─────────────────────────────────────────────────────┘
        │
        ▼ 主线程消费事件
  ┌─────────────────────────────────────────────────────┐
  │ Repl.consumeEvents(queue)                           │
  │   TEXT "我来" → System.out.print("我来")            │
  │   TEXT "帮你" → System.out.print("帮你")            │
  │   TOOL_CALL → println("[调用工具: read_file]")      │
  │   TOOL_RESULT → println("[✓ ...]")                  │
  │   TEXT "你的 pom.xml..." → print                    │
  │   DONE → return                                     │
  └─────────────────────────────────────────────────────┘
        │
        ▼
  session.addMessage(assistant, fullText)
  System.out.println()  // 换行
  → "> " 等待下一条输入
```

---

## 五、关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| LLM 协议 | 手写 HTTP + SSE 解析 | 避免 SDK 黑盒，完全控制请求体 |
| Agent 循环 | 后台线程 + BlockingQueue 事件 | Agent 与 UI 解耦，可取消 |
| 工具并发 | 只读并发 + 写入串行 | 安全优先，文件一致性 |
| 权限位置 | ToolExecutor 内部 | 对 AgentLoop 透明，不改调度逻辑 |
| MCP 串行 | 每 Server 单消费线程 | stdio 管道的物理约束 |
| System Prompt | 模块化 + 按优先级拼装 | 插拔灵活，新增指令不改 Builder |
| 缓存策略 | system 角色在 messages[0] | Anthropic 原生缓存，环境信息走补充消息 |
| 会话格式 | JSON 文件 | 人可读，Jackson 原生 |
| CLI 框架 | JLine3 | 跨平台行编辑/历史/Tab补全 |
| HTTP 客户端 | java.net.http | JDK 内置，零依赖 |
