---
title: "spec"
author: wangzijie
date: 2026-08-05
---

# MewCode 第11章 - Hook 系统 Spec

## 目标
Agent 生命周期 11 个关键节点挂自动化动作。YAML 三要素声明式，拦截/注入/通知全自动。

## 事件
会话: session_start/end | 轮次: round_start/end | 消息: pre_llm/post_llm
工具: pre_tool/post_tool（pre_tool 可拦截）| 系统: startup/shutdown

## 条件
四种匹配(修改了权限规则的 RuleEngine 使用同套匹配器):
exact/not/regex/glob, 组合 all_of/any_of

## 四种动作
command(含拦截码2)/prompt/http/subagent(占位)
三种控制: once/async(拦截类禁用)/timeout

## 规则文件
~/.mewcode/hooks.yaml + .mewcode/hooks.yaml 两层合并
