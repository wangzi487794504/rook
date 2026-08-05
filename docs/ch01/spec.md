# MewCode 第1章 - 对话交互 Spec

## 背景

当前项目仅有一个空壳 `Main.java` 和基础 `pom.xml`。
需要从零搭建 MewCode 的第一个可运行版本——一个支持多模型后端的终端对话助手。
后续章节将在对话基础上逐步添加 tool use、文件操作、代码编辑等 Agent 功能。

## 目标

- 用户在终端启动 MewCode，进入交互式 REPL 对话界面
- 支持通过 YAML 配置文件同时管理多个 LLM Provider（Anthropic / OpenAI 协议）
- 用户输入问题后，MewCode 调用 LLM API，流式逐字打印回复（SSE）
- 支持多轮对话记忆，会话持久化到本地文件，下次启动可恢复
- 运行时可通过命令切换 Provider

## 功能需求

### 配置管理
- F1: 支持 YAML 格式配置文件，可同时定义多个 LLM Provider
- F2: 每个 Provider 包含四个字段：`protocol`（协议类型）、`model`（模型名）、`base_url`（请求地址）、`api_key`（认证密钥）
- F3: 启动时自动读取配置文件，加载所有 Provider 信息

### CLI 交互
- F4: 启动后进入交互式 REPL 界面，以 `>` 作为输入提示符
- F5: 支持 `:help`、`:exit`、`:switch <provider名称>` 等命令
- F6: 历史命令持久化（上下箭头回溯）

### Provider 抽象
- F7: 提供统一的 LLM Provider 抽象接口，封装 API 调用
- F8: 实现 Anthropic 协议 Provider（支持 Messages API、streaming、extended thinking）
- F9: 实现 OpenAI 协议 Provider（支持 Chat Completions API、streaming）
- F10: 运行时可通过 `:switch` 命令切换当前使用的 Provider

### 对话
- F11: 用户输入后，调用当前 Provider 的流式 API，逐字符/逐 token 实时打印回复（打字机效果）
- F12: 支持多轮对话，AI 能记住上下文历史
- F13: 对话会话持久化到本地文件，`:exit` 退出时自动保存，下次启动自动恢复

## 非功能需求

- N1: 启动时间不超过 2 秒（从输入命令到显示提示符）
- N2: 首个 token 响应时间不超过 3 秒（用户按回车到出现第一段文字）
- N3: 流式输出过程中无明显卡顿或闪烁
- N4: 配置读取失败时给出明确错误信息，不应静默崩溃
- N5: API 调用失败时提示用户具体错误信息并退出（如超时、密钥错误、网络不通）
- N6: 代码使用 Java 17，Maven 管理依赖
- N7: 遵循项目 CLAUDE.md 中的代码规范（中文注释、Javadoc、命名规范等）

## 不做的事（本章明确排除）

- Tool use / Function calling
- 文件读写操作（Agent 模式）
- 代码编辑 / 补丁生成
- 多行输入编辑
- 语法高亮（代码块着色）
- MCP 协议支持
- Token 计数 / 用量统计
- 用户管理 / 多会话管理
- GUI / Web 界面

## 验收标准

### 配置管理
- AC1: 启动时读取 `config.yaml`，若文件不存在或格式错误，打印明确错误信息并退出
- AC2: 配置文件可定义多个 Provider，每个 Provider 的四个字段（protocol/model/base_url/api_key）均有值校验

### CLI 交互
- AC3: 启动 MewCode 后，终端显示 `>` 提示符，等待用户输入
- AC4: 输入 `:help` 显示所有可用命令及说明
- AC5: 输入 `:exit` 正常退出，终端回到标准 shell
- AC6: 按 ↑ 可回溯上一条输入，按 ↓ 回到下一条

### Provider
- AC7: 使用 Anthropic 协议 Provider 发送一条消息，能收到流式 SSE 回复
- AC8: 使用 OpenAI 协议 Provider 发送一条消息，能收到流式 SSE 回复
- AC9: 输入 `:switch <名称>` 切换当前 Provider，再次对话使用新 Provider

### 对话
- AC10: 输入「你好」回车后，终端像打字机一样逐字打印 AI 回复（非一次性输出）
- AC11: 连续发三条消息，AI 的回复能引用之前对话中的内容（多轮记忆）
- AC12: `:exit` 退出后重新启动，AI 仍能记得上次对话内容（会话恢复）

### 健壮性
- AC13: 配置了错误的 API Key，发消息时不崩溃，提示「认证失败，请检查 api_key」
- AC14: 网络不可达时，提示「网络连接失败，请检查网络和 base_url」
