---
title: "task"
author: wangzijie
date: 2026-08-05
---

## T1: ProjectInstructions
**文件:** `memory/ProjectInstructions.java`
三层CLAUDE.md + @include(visited防环/3层/项目内限制)

## T2: SessionArchiver
**文件:** `memory/SessionArchiver.java`
JSONL追加/恢复(坏行跳过)/清理(30天)

## T3: MemoryIndex
**文件:** `memory/MemoryIndex.java`
索引读写, 200行/25KB限制

## T4: AutoMemory
**文件:** `memory/AutoMemory.java`
异步LLM, 四类笔记, 去重

## T5: Session 改 JSONL
**文件:** `session/Session.java`

## T6: AgentLoop 集成
**文件:** `core/agent/AgentLoop.java`

## T7: App 集成
**文件:** `core/App.java`

## T8: CustomInstructionsModule 注入
**文件:** `prompt/modules/CustomInstructionsModule.java`

## T9: Repl :sessions
**文件:** `cli/Repl.java`

## T10: 测试
MemoryIndexTest / ProjectInstructionsTest
