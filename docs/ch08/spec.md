# MewCode 第8章 - 记忆与上下文持久化 Spec

## 背景
当前每启动都是空白大脑，不记得项目、不记得用户偏好。

## 目标
三套机制：项目指令文件(长期)、自动笔记(长期)、会话存档(工作)

## 功能需求
### 项目指令 (F1-F6)
- F1-F2: 三层加载(项目根 > .mewcode/ > 用户 ~/.mewcode/)，高优先级在前
- F3-F5: @include 引用(3层嵌套/防环/限项目内)
- F6: 注入 CustomInstructionsModule

### 会话存档 (F7-F14)
- F7-F8: JSONL逐行追加，ID格式 YYYYMMDD-HHMMSS-xxxx
- F9-F10: 恢复容错(坏行跳过/工具配对截断)
- F11: token超限先压缩
- F12: 时间跨度提醒
- F13-F14: 无meta文件/30天清理

### 自动记忆 (F15-F21)
- F15: Agent Loop后异步调LLM
- F16: 四类笔记(user/feedback/project/reference)
- F17-F18: Markdown+frontmatter, LLM去重
- F19: 用户级/项目级分开
- F20-F21: MEMORY.md索引<200行/25KB, 注入SystemPrompt

## 不做的事
向量数据库/RAG/团队同步/GUI编辑

## 验收标准 AC1-AC8
