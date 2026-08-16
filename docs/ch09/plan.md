---
title: "plan"
author: wangzijie
date: 2026-08-05
---

# MewCode 第9章 Plan — 斜杠命令系统

## 架构
新包 `cmd/`。Repl 实现 Console 接口，CommandDispatcher 处理所有 `/` 前缀输入。

## 新增 cmd/
- Command.java — record(name, aliases, description, type, hidden, handler)
- CommandType.java — enum LOCAL/UI_AFFECTING/PROMPT_FEED
- CommandRegistry.java — Map注册+别名冲突检测panic
- CommandDispatcher.java — /xxx解析→匹配→执行;未匹配→引导
- Console.java — UI抽象接口
- CompletionHandler.java — Tab补全菜单

## 12内置命令
/help /exit /plan /do /compact /clear /session /memory /permission /status /review /resume

## 变更
- Repl.java → 实现Console, 用Dispatcher替代switch-case
