---
title: "plan"
author: wangzijie
date: 2026-08-05
---

# MewCode 第5章 - 权限系统 Plan

## 架构概览

权限引擎作为独立层插入 ToolExecutor 之前，所有工具调用必经五层检查。

                        ToolExecutor.execute(calls)
                               │
                               ▼
                    ┌─────────────────────┐
                    │   PermissionEngine   │
                    │     .check(call)     │
                    └──────────┬──────────┘
                               │
            ┌──────────────────┼──────────────────┐
            ▼                  ▼                  ▼
      ┌──────────┐      ┌──────────┐      ┌──────────────┐
      │ 1.黑名单  │  →   │ 2.沙箱   │  →   │ 3.规则引擎    │
      │ (内置正则) │      │ (前缀+SYM)│      │ (3层YAML)    │
      └──────────┘      └──────────┘      └──────────────┘
                                                  │
                                    ┌─────────────┼─────────────┐
                                    ▼             ▼             ▼
                              ┌──────────┐  ┌──────────┐  ┌──────────┐
                              │ 4.模式   │  │           │  │          │
                              │ 兜底     │  │           │  │          │
                              └────┬─────┘  │           │  │          │
                                   │        │           │  │          │
                              Allow │  Ask  │           │  │          │
                                │   │        │           │  │          │
                                ▼   ▼        ▼           ▼  ▼          ▼
                              执行  ┌──────────────┐
                                    │ 5.人在回路     │
                                    │ (用户确认)     │
                                    └──────────────┘
                                     │          │
                                  Allow      Deny
                                     │          │
                                     ▼          ▼
                                   执行    返回 PERMISSION_DENIED

新增组件：

  permission/
  ├── PermissionEngine.java    — 五层串联调度
  ├── PermissionResult.java    — 判定结果
  ├── PermissionMode.java      — 四档模式枚举
  ├── Blacklist.java            — 高危命令正则拦截
  ├── PathSandbox.java          — 路径前缀+符号链接解析
  ├── RuleEngine.java           — 三层 YAML 规则合并
  ├── RuleConfig.java           — 单条规则 record
  └── HumanInTheLoop.java       — 用户确认交互

变更组件：

  tools/ToolExecutor.java       — 注入 PermissionEngine
  core/agent/AgentLoop.java     — 处理 PERMISSION_DENIED 继续 Loop
  cli/Repl.java                 — :mode 命令 + 人在回路 UI
  core/App.java                 — 创建 PermissionEngine + 加载规则

## 核心数据结构

### PermissionResult

```java
enum PermissionVerdict { ALLOW, DENY, ASK }

record PermissionResult(PermissionVerdict verdict, String reason, String layer) {
    static PermissionResult allow() { return new PermissionResult(ALLOW, "", ""); }
    static PermissionResult deny(String reason, String layer) { ... }
    static PermissionResult ask(String reason) { ... }
}
```

### PermissionMode

```java
enum PermissionMode {
    DEFAULT, ACCEPT_EDITS, PLAN, BYPASS;
    PermissionVerdict verdictFor(ToolCategory cat) { ... }
}
enum ToolCategory { READ, WRITE, EXEC }
```

### RuleConfig

```java
record RuleConfig(String toolName, String pattern, boolean allow, String source) {}
```

## 五层设计

- **第1层 Blacklist**: 内置正则，仅 exec_command，不可绕过
- **第2层 PathSandbox**: Path.toRealPath() + startsWith，文件类工具
- **第3层 RuleEngine**: 三YAML合并(本地>项目>用户)，工具名+glob匹配
- **第4层 PermissionMode**: 四档模式兜底，值域 {Allow, Ask}
- **第5层 HumanInTheLoop**: JLine 交互(↑↓/123)，三种选择+持久化

## 文件组织

```
src/main/java/com/mewcode/permission/
├── PermissionEngine.java
├── PermissionResult.java
├── PermissionMode.java
├── Blacklist.java
├── PathSandbox.java
├── RuleEngine.java
├── RuleConfig.java
└── HumanInTheLoop.java
```

## 技术决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 沙箱 | Path.toRealPath() + startsWith | JDK 原生解析 symlink |
| 规则匹配 | FileSystem.getPathMatcher("glob:") | 与 GlobTool 一致 |
| 人在回路 | JLine 终端选择 | 复用现有终端 |
| 权限位置 | ToolExecutor 内部注入 | 最小改动 |
| 黑名单 | 硬编码 Pattern 列表 | 不可配置绕过 |
