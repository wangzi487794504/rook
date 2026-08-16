---
title: "plan"
author: wangzijie
date: 2026-08-05
---

# MewCode 第4章 - 结构化 System Prompt Plan

## 架构概览

本章新增 `prompt/` 包，将 Repl 中的字符串拼接替换为模块化的 PromptBuilder。

                          ┌──────────────────────────┐
                          │     PromptBuilder         │
                          │  (按优先级排序 + 拼装)     │
                          └───────────┬──────────────┘
                                      │
                    ┌─────────────────┼─────────────────┐
                    ▼                 ▼                   ▼
              ┌──────────┐    ┌──────────┐       ┌──────────────┐
              │ 固定模块   │    │ 可选模块   │       │  环境模块      │
              │ ×7        │    │ ×3        │       │  (动态)       │
              └──────────┘    └──────────┘       └──────────────┘
                    │                 │                   │
                    └─────────────────┼───────────────────┘
                                      ▼
                            ┌──────────────────┐
                            │  System Prompt   │
                            │  文本（缓存通道）  │
                            └──────────────────┘
                                      │
                              messages[0].role = "system"

新增组件:

  prompt/
  ├── PromptModule.java        — 模块抽象（名称、优先级、内容生成）
  ├── PromptBuilder.java        — 装配器（排序 + 拼接）
  ├── PromptContext.java        — 渲染上下文
  ├── SupplementMessage.java   — 补充消息构造器
  └── modules/
      ├── IdentityModule.java       — 固定1: 身份
      ├── SystemConstraintsModule.java — 固定2: 系统约束
      ├── TaskModeModule.java       — 固定3: 任务模式
      ├── ActionExecutionModule.java — 固定4: 动作执行
      ├── ToolUsageModule.java      — 固定5: 工具使用
      ├── ToneStyleModule.java      — 固定6: 语气风格
      ├── TextOutputModule.java     — 固定7: 文本输出
      ├── CustomInstructionsModule.java — 可选
      ├── ActiveSkillsModule.java   — 可选
      └── LongTermMemoryModule.java  — 可选

变更组件:

  llm/Usage.java            — 扩展缓存字段
  llm/AnthropicProvider.java — 解析 message_start 中的缓存 usage
  core/agent/AgentLoop.java — 注入 system prompt + 补充消息 + 注入频率控制
  cli/Repl.java             — 移除 injectSystemPrompt，改用 PromptBuilder
  core/App.java             — 创建 PromptBuilder 并注入
  tools/impl/*.java         — description 加 [重要] 双重强化

## 核心数据结构

### PromptModule（新增）

```java
interface PromptModule {
    String name();
    int priority();  // 1-70 固定模块, 71-100 可选模块
    String render(PromptContext ctx);
}
```

### PromptContext（新增）

```java
record PromptContext(
    boolean planMode,
    int iteration,
    String workingDir,
    String os,
    List<String> activeSkills
) {}
```

### PromptBuilder（新增）

```java
class PromptBuilder {
    void register(PromptModule module);
    String build(PromptContext ctx);
    List<PromptModule> getModules();  // 按优先级排序
}
```

### SupplementMessage（新增）

```java
class SupplementMessage {
    static ChatMessage create(String instruction);
}
```

### Usage 扩展

```java
record Usage(
    int inputTokens,
    int outputTokens,
    int cacheReadInputTokens,
    int cacheCreationInputTokens
) {}
```

### 注入频率控制（AgentLoop 内）

```
Plan Mode 下每轮循环开始时：
  iteration == 1     → 注入完整提示
  iteration % 3 == 0 → 注入完整提示
  其他              → 注入精简版 "[Supplement] 仍在计划模式，只读。"
```

### 双重强化

工具 description: `[重要] 编辑文件前必须先读取文件内容。`
System Prompt 工具使用模块: 同样规则 + 更详细解释。

### 文件组织

```
src/main/java/com/mewcode/
├── prompt/
│   ├── PromptModule.java
│   ├── PromptBuilder.java
│   ├── PromptContext.java
│   ├── SupplementMessage.java
│   └── modules/
│       ├── IdentityModule.java
│       ├── SystemConstraintsModule.java
│       ├── TaskModeModule.java
│       ├── ActionExecutionModule.java
│       ├── ToolUsageModule.java
│       ├── ToneStyleModule.java
│       ├── TextOutputModule.java
│       ├── CustomInstructionsModule.java
│       ├── ActiveSkillsModule.java
│       └── LongTermMemoryModule.java
├── llm/Usage.java              — ★ 扩展
├── llm/AnthropicProvider.java  — ★ 缓存解析
├── core/agent/AgentLoop.java   — ★ Prompt + 补充消息 + 频率控制
├── cli/Repl.java               — ★ 移除 injectSystemPrompt
└── tools/impl/*.java           — ★ description 强化

src/test/java/com/mewcode/prompt/
├── PromptBuilderTest.java
└── SupplementMessageTest.java
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 模块渲染上下文 | `PromptContext` record | 避免模块依赖全局状态 |
| 补充消息格式 | `[Supplement]` 前缀 user 消息 | 不被模型当用户输入困惑 |
| 缓存通道 | system 角色在 messages[0] | Anthropic 原生支持缓存 |
| 注入频率 | 间隔 3 轮硬编码 | 简单有效 |
| 环境信息 | 独立 system 消息 | 不污染缓存 |
