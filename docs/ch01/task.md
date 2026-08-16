---
title: "task"
author: wangzijie
date: 2026-08-05
---

# MewCode 第1章 - 对话交互 Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 修改 | `pom.xml` | 添加 JLine3、SnakeYAML 依赖 |
| 新建 | `config.yaml` | 默认配置文件 |
| 新建 | `src/main/java/com/mewcode/llm/ChatMessage.java` | 消息 record |
| 新建 | `src/main/java/com/mewcode/llm/StreamCallback.java` | 流式回调接口 |
| 新建 | `src/main/java/com/mewcode/config/ProviderConfig.java` | Provider 配置 record |
| 新建 | `src/main/java/com/mewcode/config/AppConfig.java` | 全局配置 record |
| 新建 | `src/main/java/com/mewcode/config/ConfigLoader.java` | YAML 加载 + 校验 |
| 新建 | `src/main/java/com/mewcode/llm/LlmProvider.java` | Provider 抽象接口 |
| 新建 | `src/main/java/com/mewcode/session/Session.java` | 会话管理 + 持久化 |
| 新建 | `src/main/java/com/mewcode/llm/AnthropicProvider.java` | Anthropic 协议实现 |
| 新建 | `src/main/java/com/mewcode/llm/OpenAiProvider.java` | OpenAI 协议实现 |
| 新建 | `src/main/java/com/mewcode/llm/ProviderFactory.java` | Provider 工厂 |
| 新建 | `src/main/java/com/mewcode/cli/Repl.java` | JLine3 REPL 循环 |
| 新建 | `src/main/java/com/mewcode/core/App.java` | 启动协调 |
| 修改 | `src/main/java/com/mewcode/cli/Main.java` | 入口适配 |
| 新建 | `src/test/java/com/mewcode/config/ConfigLoaderTest.java` | 配置加载测试 |
| 新建 | `src/test/java/com/mewcode/session/SessionTest.java` | 会话测试 |
| 新建 | `src/test/java/com/mewcode/llm/ProviderFactoryTest.java` | 工厂测试 |

## T1: 更新 pom.xml 依赖

**文件：** `pom.xml`
**依赖：** 无
**步骤：**
1. 添加 JLine3 依赖（`org.jline:jline:3.26.3`）
2. 添加 SnakeYAML 依赖（`org.yaml:snakeyaml:2.3`）
3. 运行 `bash mvnw compile` 确认依赖下载成功

**验证：** `bash mvnw dependency:tree` 能看到 JLine3 和 SnakeYAML

## T2: 创建 ChatMessage 和 StreamCallback

**文件：**
- `src/main/java/com/mewcode/llm/ChatMessage.java`
- `src/main/java/com/mewcode/llm/StreamCallback.java`

**依赖：** T1
**步骤：**
1. 创建 `ChatMessage` record，包含 `role`（String）和 `content`（String）字段，使用 Jackson `@JsonIgnoreProperties` 注解
2. 创建 `StreamCallback` 接口，定义 `void onToken(String token)` 方法
3. 编译验证

**验证：** `bash mvnw compile -pl .` 编译通过

## T3: 创建配置类 ProviderConfig 和 AppConfig

**文件：**
- `src/main/java/com/mewcode/config/ProviderConfig.java`
- `src/main/java/com/mewcode/config/AppConfig.java`

**依赖：** T1
**步骤：**
1. 创建 `ProviderConfig` record：`name`、`protocol`、`model`、`baseUrl`、`apiKey`
2. 创建 `AppConfig` record：`providers`（List\<ProviderConfig\>）、`defaultProvider`（String）
3. 编译验证

**验证：** `bash mvnw compile -pl .` 编译通过

## T4: 创建 ConfigLoader

**文件：** `src/main/java/com/mewcode/config/ConfigLoader.java`
**依赖：** T3
**步骤：**
1. 实现 `load(Path path)` 方法，使用 SnakeYAML 解析配置文件
2. 校验 `providers` 列表非空
3. 校验每个 provider 的四个字段（name/protocol/model/apiKey）非空
4. 校验 `defaultProvider` 在 providers 列表中存在
5. 校验 protocol 为 "anthropic" 或 "openai"（大小写不敏感）
6. 创建 `src/main/resources/config.yaml` 默认配置模板

**验证：** `bash mvnw compile -pl .` 编译通过

## T5: 创建 LlmProvider 接口

**文件：** `src/main/java/com/mewcode/llm/LlmProvider.java`
**依赖：** T2
**步骤：**
1. 定义 `LlmProvider` 接口
2. 方法：`String name()` — 返回 provider 名称
3. 方法：`void stream(List<ChatMessage> messages, StreamCallback callback)` — 流式调用
4. 方法：`String complete(List<ChatMessage> messages)` — 同步调用，内部调用 stream 并拼接结果

**验证：** `bash mvnw compile -pl .` 编译通过

## T6: 创建 Session

**文件：** `src/main/java/com/mewcode/session/Session.java`
**依赖：** T2
**步骤：**
1. 实现 `Session` 类，内部用 `List<ChatMessage>` 存储消息历史
2. `getMessages()` — 返回不可变列表
3. `addMessage(ChatMessage msg)` — 追加消息
4. `save(Path path)` — 使用 Jackson 将消息列表序列化为 JSON 写入文件
5. `static load(Path path)` — 从 JSON 文件反序列化为 Session
6. 确保 sessions 目录在保存时自动创建

**验证：** `bash mvnw compile -pl .` 编译通过

## T7: 创建 AnthropicProvider

**文件：** `src/main/java/com/mewcode/llm/AnthropicProvider.java`
**依赖：** T5
**步骤：**
1. 实现 `LlmProvider` 接口
2. 构造方法接收 `ProviderConfig`
3. `stream()` 方法：
   - 用 `HttpClient` 构建 POST 请求到 `{baseUrl}/messages`
   - 设置请求头：`x-api-key`、`anthropic-version: 2023-06-01`、`content-type: application/json`
   - 请求体包含 `model`、`max_tokens: 4096`、`messages`（从 List\<ChatMessage\> 转换）、`stream: true`
   - 接收 SSE 响应流，解析 `content_block_delta` 事件中的 `delta.text`
   - 每次拿到 text 调用 `callback.onToken(text)`
4. 解析异常时抛出包含 HTTP 状态码和错误信息的具体异常

**验证：** `bash mvnw compile -pl .` 编译通过

## T8: 创建 OpenAiProvider

**文件：** `src/main/java/com/mewcode/llm/OpenAiProvider.java`
**依赖：** T5
**步骤：**
1. 实现 `LlmProvider` 接口
2. 构造方法接收 `ProviderConfig`
3. `stream()` 方法：
   - 用 `HttpClient` 构建 POST 请求到 `{baseUrl}/chat/completions`
   - 设置请求头：`Authorization: Bearer {key}`、`content-type: application/json`
   - 请求体包含 `model`、`messages`、`stream: true`
   - 接收 SSE 响应流，解析 `choices[0].delta.content`
   - 每次拿到 content 调用 `callback.onToken(content)`
4. 解析异常时抛出包含 HTTP 状态码和错误信息的具体异常

**验证：** `bash mvnw compile -pl .` 编译通过

## T9: 创建 ProviderFactory

**文件：** `src/main/java/com/mewcode/llm/ProviderFactory.java`
**依赖：** T7, T8
**步骤：**
1. 实现 `static Map<String, LlmProvider> create(AppConfig config)` 方法
2. 遍历 `config.providers()`，根据 `protocol` 字段创建 AnthropicProvider 或 OpenAiProvider
3. 以 `name` 为 key 存入 Map
4. 若 protocol 不识别，抛出 `IllegalArgumentException`

**验证：** `bash mvnw compile -pl .` 编译通过

## T10: 创建 Repl（REPL 循环）

**文件：** `src/main/java/com/mewcode/cli/Repl.java`
**依赖：** T5, T6, T9
**步骤：**
1. 构造方法接收 `Map<String, LlmProvider>` 和 `Session`
2. `start()` 方法：
   - 用 JLine3 的 `Terminal` + `LineReader` + 历史记录
   - 设定 `>` 提示符
   - 进入无限循环读取用户输入：
     - 空输入 → 继续
     - `:help` → 打印命令列表
     - `:exit` / `:quit` / `:q` → break
     - `:switch <name>` → 切换 currentProvider
     - `:model` → 显示当前 provider 和 model
     - `:clear` → 清空 session 消息
     - 其他 → 进入对话流
3. `processMessage(String input)` 处理对话（调用 provider.stream + 逐字打印 + 收集完整回复）
4. 退出时调用 `session.save()`

**验证：** `bash mvnw compile -pl .` 编译通过

## T11: 创建 App（启动协调）

**文件：** `src/main/java/com/mewcode/core/App.java`
**依赖：** T4, T6, T9, T10
**步骤：**
1. `run(Path configPath)` 方法：
   - 调用 `ConfigLoader.load(configPath)` 获取 AppConfig
   - 调用 `ProviderFactory.create(appConfig)` 获取 providers Map
   - 从 providers Map 取出默认 provider
   - 确定 session 文件路径：`~/.mewcode/sessions/default.json`
   - 调用 `Session.load(sessionPath)` 恢复会话（文件不存在则新建）
   - 创建 `Repl` 实例并调用 `start()`
   - 退出时用 try-finally 确保 session.save() 被调用

**验证：** `bash mvnw compile -pl .` 编译通过

## T12: 修改 Main.java

**文件：** `src/main/java/com/mewcode/cli/Main.java`
**依赖：** T11
**步骤：**
1. 解析命令行参数 `--config`，默认值 `./config.yaml`
2. 调用 `new App().run(Paths.get(configPath))`
3. 最外层用 try-catch 打印错误信息

**验证：** `bash mvnw compile -pl .` 编译通过

## T13: 编写配置加载单元测试

**文件：** `src/test/java/com/mewcode/config/ConfigLoaderTest.java`
**依赖：** T4
**步骤：**
1. 测试正常配置加载
2. 测试文件不存在 → 抛出异常
3. 测试字段缺失 → 抛出异常
4. 测试 protocol 不合法 → 抛出异常
5. 测试 defaultProvider 不存在 → 抛出异常

**验证：** `bash mvnw test -pl . -Dtest=ConfigLoaderTest` 全部通过

## T14: 编写 Session 单元测试

**文件：** `src/test/java/com/mewcode/session/SessionTest.java`
**依赖：** T6
**步骤：**
1. 测试添加消息后能获取
2. 测试 save + load 往返一致
3. 测试加载不存在文件返回空 Session
4. 测试多轮对话消息顺序正确

**验证：** `bash mvnw test -pl . -Dtest=SessionTest` 全部通过

## T15: 编写 ProviderFactory 单元测试

**文件：** `src/test/java/com/mewcode/llm/ProviderFactoryTest.java`
**依赖：** T9
**步骤：**
1. 测试通过 Anthropic protocol 创建 → 返回 AnthropicProvider
2. 测试通过 OpenAI protocol 创建 → 返回 OpenAiProvider
3. 测试非法 protocol → 抛出异常

**验证：** `bash mvnw test -pl . -Dtest=ProviderFactoryTest` 全部通过

## 执行顺序

```
T1 ──→ T2 ──→ T5 ──→ T7 ──┐
  │     │                   ├──→ T9 ──┐
  │     └──→ T6 ────────────┘         │
  │                                    ├──→ T10 ──→ T11 ──→ T12
  └──→ T3 ──→ T4 ─────────────────────┘
              │
              └──→ T13 (可与 T5-T12 并行)

T2 ──→ T6 ──→ T14 (可与 T7-T12 并行)
T9 ──→ T15 (可与 T10-T12 并行)
```
