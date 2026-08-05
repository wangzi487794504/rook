# MewCode 第8章 Plan

## 架构
新增 `memory/` 包。三个子系统在启动时并行加载，注入 System Prompt。
Agent Loop 每轮结束后异步更新记忆笔记。

## 新增
memory/
├── ProjectInstructions.java — 三层CLAUDE.md加载+@include(防环/嵌套3层/限项目内)
├── SessionArchiver.java — JSONL追加写/恢复/30天清理
├── AutoMemory.java — 异步LLM更新四类笔记(CompletableFuture)
├── MemoryIndex.java — MEMORY.md索引(200行/25KB)

## 变更
- session/Session.java — JSONL格式
- core/App.java — 加载指令+记忆
- core/agent/AgentLoop.java — 每轮后异步触发AutoMemory
- prompt/CustomInstructionsModule.java — 接收注入
- cli/Repl.java — :sessions

## 决策
| 点 | 选择 |
|----|------|
| 会话 | JSONL追加 |
| @include | 正则+递归 |
| 去重 | LLM判断 |
| 异步 | CompletableFuture |
