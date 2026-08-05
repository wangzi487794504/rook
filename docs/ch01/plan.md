# MewCode 第1章 - 对话交互 Plan

## 架构概览

整个程序分为五个模块，启动时自顶向下串联，运行时由 CLI 模块驱动交互循环：

```
                    ┌─────────────────┐
                    │    CLI 入口       │
                    │   (Main.java)    │
                    └────────┬────────┘
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
        ┌──────────┐  ┌──────────┐  ┌──────────────┐
        │  配置模块  │  │  会话模块  │  │  Provider 层  │
        │  config   │  │ session   │  │   llm         │
        └──────────┘  └──────────┘  └──────────────┘
                                           │
                                ┌──────────┼──────────┐
                                ▼                     ▼
                        ┌──────────────┐  ┌──────────────┐
                        │ Anthropic     │  │   OpenAI      │
                        │ Provider      │  │   Provider    │
                        └──────────────┘  └──────────────┘
```

| 模块 | 职责 |
|------|------|
| **cli** | JLine3 REPL 循环、命令解析、用户输入处理 |
| **config** | 读取 YAML 配置文件，校验并构造 Provider 配置对象 |
| **session** | 对话上下文存储、持久化到本地 JSON 文件、恢复 |
| **llm** | Provider 抽象接口 + Anthropic 和 OpenAI 两种协议的实现、SSE 流式调用 |
| **core** | 按顺序初始化各模块，串联启动流程 |

- **模块间单向依赖**：core → cli / config / session / llm。cli 持有 session 和 llm 的引用
- **无循环依赖**

## 核心数据结构

### ProviderConfig（配置层）
```java
record ProviderConfig(
    String name,       // Provider 名称，如 "deepseek"、"claude"
    String protocol,   // "anthropic" 或 "openai"
    String model,      // 模型名，如 "deepseek-v4-pro"
    String baseUrl,    // API 地址
    String apiKey      // 认证密钥
) {}
```

### ChatMessage（消息体）
```java
record ChatMessage(
    String role,       // "user" | "assistant" | "system"
    String content     // 消息内容
) {}
```

### StreamCallback（流式回调接口）
```java
@FunctionalInterface
interface StreamCallback {
    void onToken(String token);  // 每收到一个文本块就调用一次
}
```

### LlmProvider 接口（Provider 抽象层）

```java
interface LlmProvider {
    /** 返回 Provider 名称（对应配置中的 name） */
    String name();

    /** 发送消息并流式返回，每收到一个 token 就回调 StreamCallback */
    void stream(List<ChatMessage> messages, StreamCallback callback);

    /** 发送消息，同步等待完整回复后返回 */
    String complete(List<ChatMessage> messages);
}
```

LLM 调用用 Java 11 `java.net.http.HttpClient` + SSE 解析。

### Session（会话层）

```java
class Session {
    /** 消息历史列表 */
    List<ChatMessage> getMessages();

    /** 添加一条消息 */
    void addMessage(ChatMessage msg);

    /** 保存到文件 */
    void save(Path path);

    /** 从文件恢复 */
    static Session load(Path path);
}
```

### AppConfig（全局配置）

```java
record AppConfig(
    List<ProviderConfig> providers,  // 所有 Provider 配置
    String defaultProvider           // 默认使用的 Provider 名称
) {}
```

## 模块设计

### 模块 A：config — 配置管理

**职责：** 读取 YAML 配置文件，解析为 `AppConfig`，校验各字段非空

**对外接口：**
- `ConfigLoader.load(Path path) → AppConfig` — 读取并校验配置

**依赖：** 无外部模块依赖，仅依赖 SnakeYAML 库

**配置文件格式（config.yaml）：**

```yaml
default_provider: deepseek

providers:
  - name: deepseek
    protocol: anthropic
    model: deepseek-v4-pro
    base_url: https://api.deepseek.com/anthropic
    api_key: sk-xxxx

  - name: gpt
    protocol: openai
    model: gpt-4o
    base_url: https://api.openai.com/v1
    api_key: sk-xxxx
```

---

### 模块 B：llm — Provider 抽象层

**职责：** 提供统一 LLM 调用接口，封装 Anthropic/OpenAI 两种协议差异

**对外接口：**
- `LlmProvider` 接口（见上文）
- `AnthropicProvider` — 实现 Anthropic Messages API + SSE 流式
- `OpenAiProvider` — 实现 OpenAI Chat Completions API + SSE 流式
- `ProviderFactory.create(ProviderConfig) → LlmProvider` — 根据 protocol 字段创建对应实现

**依赖：** config 模块（接收 ProviderConfig），Java Http Client

**关键差异处理：**

| 差异点 | Anthropic | OpenAI |
|--------|-----------|--------|
| 请求路径 | `{base_url}/messages` | `{base_url}/chat/completions` |
| 认证头 | `x-api-key: {key}` | `Authorization: Bearer {key}` |
| 版本头 | `anthropic-version: 2023-06-01` | 无需 |
| 流式标志 | `stream: true` | `stream: true` |
| SSE 事件 | `data: {"type": "content_block_delta"...}` | `data: {"choices": [{"delta": {"content": "..."}}]}` |
| Extended Thinking | `thinking` 参数 + `thinking` content block | 不支持（OpenAI 实现忽略） |

---

### 模块 C：session — 会话管理

**职责：** 管理多轮对话的消息历史，支持持久化到本地 JSON

**对外接口：**
- `getMessages() → List<ChatMessage>`
- `addMessage(ChatMessage msg)`
- `save(Path path)` — 保存到文件
- `load(Path path) → Session` — 从文件恢复

**依赖：** Jackson（JSON 序列化）

---

### 模块 D：cli — 交互界面

**职责：** JLine3 REPL 循环，解析用户输入，分发命令或调用 LLM

**对外接口：**
- `Repl.start()` — 启动交互循环，阻塞直到用户退出

**内部命令：**
- `:help` — 打印帮助信息
- `:exit` / `:quit` / `:q` — 保存会话并退出
- `:switch <name>` — 切换到指定 Provider
- `:model` — 显示当前使用的 Provider 和模型
- `:clear` — 清空当前对话历史

**依赖：** JLine3（行编辑/历史）、config、session、llm

## 模块交互

### 启动流程

```
Main.main()
  │
  ├─1. ConfigLoader.load("config.yaml") → AppConfig
  ├─2. ProviderFactory.create(config) → Map<String, LlmProvider>
  ├─3. Session.load(sessionPath) → Session
  ├─4. new Repl(providers, session)
  └─5. repl.start()
         │
         └─ 阻塞循环，等待用户输入
```

### 对话流程

```
用户输入 "今天天气不错"
  │
  ▼
Repl 判断：不是命令 → 当作对话消息
  │
  ├─1. session.addMessage(new ChatMessage("user", "今天天气不错"))
  ├─2. currentProvider.stream(session.getMessages(), callback)
  │      │
  │      ├─ HTTP POST {base_url}/messages (Anthropic) 或 /chat/completions (OpenAI)
  │      ├─ 接收 SSE 流
  │      └─ 每收到 token → callback.onToken(token)
  │                               │
  │                               └─ System.out.print(token)
  │
  └─3. 收集完整回复 → session.addMessage(new ChatMessage("assistant", fullReply))
```

### 切换 Provider 流程

```
用户输入 ":switch gpt"
  │
  ▼
Repl 匹配 :switch 命令
  │
  ├─1. 从 providers map 查找 key "gpt"
  ├─2. 找到 → 设置 currentProvider = providers["gpt"]
  │           打印 "已切换到 gpt (model: gpt-4o)"
  └─3. 找不到 → 打印 "Provider 'gpt' 不存在，可用: [deepseek, gpt]"
```

## 文件组织

```
mewcode/
├── pom.xml
├── config.yaml                        ← 默认配置文件
├── CLAUDE.md
├── docs/ch01/
│   ├── spec.md
│   ├── plan.md
│   ├── task.md
│   └── checklist.md
├── src/main/java/com/mewcode/
│   ├── cli/
│   │   ├── Main.java                  ← 入口：解析 --config 参数，启动 REPL
│   │   └── Repl.java                  ← JLine3 REPL 循环、命令解析
│   ├── config/
│   │   ├── ConfigLoader.java          ← YAML 读取 + 校验
│   │   ├── AppConfig.java             ← record: 全局配置
│   │   └── ProviderConfig.java        ← record: 单个 Provider 配置
│   ├── llm/
│   │   ├── LlmProvider.java           ← 接口
│   │   ├── StreamCallback.java        ← 流式回调接口
│   │   ├── ChatMessage.java           ← record: 消息
│   │   ├── AnthropicProvider.java     ← Anthropic 协议实现
│   │   ├── OpenAiProvider.java        ← OpenAI 协议实现
│   │   └── ProviderFactory.java       ← 根据 protocol 创建对应实例
│   ├── session/
│   │   └── Session.java               ← 消息历史管理 + 持久化
│   └── core/
│       └── App.java                   ← 启动协调：加载配置→创建Provider→加载Session→启动REPL
├── src/test/java/com/mewcode/
│   ├── config/
│   │   └── ConfigLoaderTest.java
│   ├── llm/
│   │   ├── AnthropicProviderTest.java
│   │   ├── OpenAiProviderTest.java
│   │   └── ProviderFactoryTest.java
│   └── session/
│       └── SessionTest.java
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| HTTP 客户端 | Java 11 `java.net.http.HttpClient` | JDK 内置，零依赖，原生 SSE 支持 |
| CLI 框架 | JLine3 | 跨平台行编辑、历史、Tab 补全 |
| YAML 解析 | SnakeYAML | Maven 生态最成熟的 YAML 库 |
| JSON 序列化 | Jackson | 已在 pom.xml，统一 JSON 处理 |
| 会话存储格式 | JSON 文件 | 人可读，Jackson 原生支持 |
| SSE 解析 | 逐行读取 `InputStream` | SDK 无关，最轻量 |
| 启动命令行参数 | `--config` 指定配置文件路径，默认 `./config.yaml` | 简单直接 |
| 会话文件位置 | `~/.mewcode/sessions/` 目录下以启动时间戳命名 | 不污染项目目录，支持多会话 |
