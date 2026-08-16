---
title: "checklist"
author: wangzijie
date: 2026-08-05
---

# MewCode 第3章 - Agent Loop Checklist

> 每一项通过运行代码或观察行为来验证，聚焦系统行为。

## 实现完整性
- [ ] 基础类型 AgentEvent/LoopState/LoopConfig/Usage 已定义（验证：编译通过）
- [ ] ChatMessage 扩展 + StreamCallback/LlmProvider 增强（验证：编译通过）
- [ ] StreamCollector 双路收集（验证：`mvnw test -Dtest=StreamCollectorTest`）
- [ ] AnthropicProvider 解析 usage 事件（验证：调用 LLM 后 lastUsage() 不为 null）
- [ ] OpenAiProvider 解析 usage 事件（验证：同上）
- [ ] ToolExecutor 保序分批并发（验证：`mvnw test -Dtest=ToolExecutorTest`）
- [ ] AgentLoop ReAct 引擎（验证：`mvnw test -Dtest=AgentLoopTest`）
- [ ] Repl 重构（AgentLoop 调度 + 事件消费 + Plan Mode）（验证：编译通过）
- [ ] App 创建配置（验证：编译通过）

## 验收标准对照

### Agent Loop
- [ ] AC1: 两步工具任务 — 发"看 config.yaml 告诉我 provider 几个"，模型两次调 read_file 后给回复（验证：端到端）
- [ ] AC2: 迭代上限 — 设 3 轮上限，模型超过时自动停止并提示（验证：单元测试模拟）
- [ ] AC3: 流式态 Ctrl+C — 停 Loop 回 `> `，不退出（验证：交互操作）
- [ ] AC4: 空闲态 Ctrl+C — 退出程序（验证：交互操作）

### 事件流
- [ ] AC5: AgentEvent 各类型可构建和消费（验证：`EventQueueTest`）
- [ ] AC6: 10 事件按序收发（验证：`EventQueueTest`）

### 流式收集器
- [ ] AC7: 同一 token 走实时 + 累积通道（验证：`StreamCollectorTest`）
- [ ] AC8: tool_use JSON 碎片拼接还原（验证：`StreamCollectorTest`）

### 并行工具执行
- [ ] AC9: 三次只读并发 < 串行总时间（验证：`ToolExecutorTest`）
- [ ] AC10: 读写混搭保序（验证：`ToolExecutorTest`）

### Plan Mode
- [ ] AC11: `:plan` 后模型只用只读工具（验证：发"修复 bug"，模型不写不改）
- [ ] AC12: `:do` 后模型接着计划执行写入操作（验证：切换后发消息，能调写工具）

### 历史一致性
- [ ] AC13: Loop 被 Ctrl+C 打断后对话历史仍合法（验证：打断后继续对话不报错）

### Token 用量
- [ ] AC14: 多轮后累计 token 递增（验证：单元测试检查 Usage 累计）

### 跨协议
- [ ] AC15: Anthropic 和 OpenAI 两协议 Agent Loop 行为一致（验证：分别用两种 provider 跑两步工具任务）

## 编译与测试
- [ ] 项目编译无错误（验证：`mvnw compile`）
- [ ] 全部测试通过（验证：`mvnw test`）

## 端到端场景

### 场景 1：多步工具链
```
1. 启动 MewCode
2. 输入「帮我看看项目里有多少 Java 文件，然后读其中一个告诉我它写了什么」
3. 观察 Agent 第1轮：调 glob 找文件
4. 观察 Agent 第2轮：调 read_file 读文件
5. Agent 给最终回复，停止
```

### 场景 2：Ctrl+C 中断
```
1. 输入一个需要多轮工具的任务
2. 工具执行过程中按 Ctrl+C
3. Loop 停止，回到 > 提示符
4. 程序不退出，可以继续对话
```

### 场景 3：Plan → Do
```
1. 输入 :plan → 提示进入计划模式
2. 输入「帮我分析这个项目结构」
3. 模型只用 glob + read_file 探索，不执行写/命令
4. 输入 :do → 提示进入执行模式
5. 后续消息可以调全工具
```

### 场景 4：迭代上限
```
1. 临时设 maxIterations=3
2. 问一个复杂任务，模型循环3轮后自动停止
3. 看到"已达到最大迭代次数"提示

### 场景 5：进度显示
```
1. 发一个多步工具任务
2. 流式态期间看到 [第 2/25 轮] 进度指示
```
