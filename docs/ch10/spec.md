---
title: "spec"
author: wangzijie
date: 2026-08-05
---

# MewCode 第10章 - Skill 系统 Spec

## 核心
- 每个Skill是一个目录，含SKILL.md + 可选tool.json/references/
- YAML frontmatter: name(必填/[a-z][a-z0-9-]*/1-32), description(必填), allowed_tools, mode(inline|fork), fork_context(none|recent|full), model
- Markdown正文含$ARGUMENTS占位符
- 三级存储: 内置<用户<项目, 同名覆盖
- 两阶段加载: 启动注name+desc / skill_load工具加载完整指令
- 工具白名单 + 双执行模式
- 自动注册斜杠命令

## 三个样板: commit/review/test
