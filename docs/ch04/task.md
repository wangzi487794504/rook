# MewCode 第4章 - 结构化 System Prompt Tasks

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | `prompt/PromptContext.java` | 渲染上下文 record |
| 新建 | `prompt/PromptModule.java` | 模块接口 |
| 新建 | `prompt/PromptBuilder.java` | 装配器 |
| 新建 | `prompt/SupplementMessage.java` | 补充消息 |
| 新建 | `prompt/modules/IdentityModule.java` | 固定1: 身份 |
| 新建 | `prompt/modules/SystemConstraintsModule.java` | 固定2: 系统约束 |
| 新建 | `prompt/modules/TaskModeModule.java` | 固定3: 任务模式 |
| 新建 | `prompt/modules/ActionExecutionModule.java` | 固定4: 动作执行 |
| 新建 | `prompt/modules/ToolUsageModule.java` | 固定5: 工具使用 |
| 新建 | `prompt/modules/ToneStyleModule.java` | 固定6: 语气风格 |
| 新建 | `prompt/modules/TextOutputModule.java` | 固定7: 文本输出 |
| 新建 | `prompt/modules/CustomInstructionsModule.java` | 可选 |
| 新建 | `prompt/modules/ActiveSkillsModule.java` | 可选 |
| 新建 | `prompt/modules/LongTermMemoryModule.java` | 可选 |
| 修改 | `llm/Usage.java` | 扩展缓存字段 |
| 修改 | `llm/AnthropicProvider.java` | 解析缓存 usage |
| 修改 | `core/agent/AgentLoop.java` | Prompt注入+频率控制 |
| 修改 | `cli/Repl.java` | 移除 injectSystemPrompt |
| 修改 | `core/App.java` | 创建 PromptBuilder |
| 修改 | `tools/impl/EditFileTool.java` | description 双重强化 |
| 新建 | `test/prompt/PromptBuilderTest.java` | 装配器测试 |
| 新建 | `test/prompt/SupplementMessageTest.java` | 补充消息测试 |

## T1: 创建 PromptContext + PromptModule

**文件：** `prompt/PromptContext.java`, `prompt/PromptModule.java`
**依赖：** 无
**步骤：**
1. `PromptContext` record: planMode, iteration, workingDir, os, activeSkills
2. `PromptModule` 接口: name(), priority(), render(PromptContext)

**验证：** `mvnw compile` 编译通过

## T2: 创建 PromptBuilder

**文件：** `prompt/PromptBuilder.java`
**依赖：** T1
**步骤：**
1. 内部 List<PromptModule>，register() 添加模块
2. build(ctx): 按 priority 排序，每个模块 render() 后拼接，模块间 "\n\n" 分隔

**验证：** `mvnw compile` 编译通过

## T3: 创建 SupplementMessage

**文件：** `prompt/SupplementMessage.java`
**依赖：** 无
**步骤：**
1. `static create(String instruction) → ChatMessage`：role="user", content="[Supplement] " + instruction

**验证：** `mvnw compile` 编译通过

## T4: 扩展 Usage

**文件：** `llm/Usage.java`
**依赖：** 无
**步骤：**
1. 新增 cacheReadInputTokens, cacheCreationInputTokens 字段（默认0）
2. 更新 ZERO 常量和 add() 方法

**验证：** `mvnw compile` 编译通过

## T5: 实现七个固定模块

**文件：** `prompt/modules/` 下 7 个文件
**依赖：** T1
**步骤：**
1. IdentityModule(p=1): Agent 身份和版本
2. SystemConstraintsModule(p=2): 硬性规则
3. TaskModeModule(p=3): planMode ? 只读模式 : 正常模式
4. ActionExecutionModule(p=4): 工具调用处理流程
5. ToolUsageModule(p=5): 通用约定 + 关键规则双重强化
6. ToneStyleModule(p=6): 中文回复、简洁明了
7. TextOutputModule(p=7): 代码块输出规范

**验证：** `mvnw compile` 编译通过

## T6: 实现三个可选模块

**文件：** `prompt/modules/CustomInstructionsModule.java` 等 3 个
**依赖：** T1
**步骤：**
1. CustomInstructionsModule(p=80): 外部设置内容，默认空
2. ActiveSkillsModule(p=85): 根据 activeSkills 生成
3. LongTermMemoryModule(p=90): 外部设置内容，默认空

**验证：** `mvnw compile` 编译通过

## T7: AnthropicProvider 缓存解析

**文件：** `llm/AnthropicProvider.java`
**依赖：** T4
**步骤：**
1. SSE 解析中新增 `message_start` 事件处理
2. 提取 `message.usage.cache_read_input_tokens` / `cache_creation_input_tokens`
3. 合并到 lastUsage

**验证：** `mvnw compile` 编译通过

## T8: AgentLoop 系统提示注入 + 频率控制

**文件：** `core/agent/AgentLoop.java`
**依赖：** T2, T3
**步骤：**
1. 构造接收 PromptBuilder
2. 每轮循环前 buildSystemMessage() 注入 messages[0]
3. Plan Mode 频率控制: iteration==1 或 iteration%3==0 → 完整补充, 否则精简
4. 非 Plan Mode 不注入

**验证：** `mvnw compile` 编译通过

## T9: 重构 Repl

**文件：** `cli/Repl.java`
**依赖：** T2
**步骤：**
1. 移除 injectSystemPrompt() 方法
2. processMessage() 不再手动注入系统消息

**验证：** `mvnw compile` 编译通过

## T10: App 创建 PromptBuilder

**文件：** `core/App.java`
**依赖：** T2, T5, T6
**步骤：**
1. 注册七个固定模块 + 三个可选模块
2. 传给 Repl 构造函数

**验证：** `mvnw compile` 编译通过

## T11: 工具描述双重强化

**文件：** `tools/impl/EditFileTool.java`
**依赖：** 无
**步骤：**
1. EditFileTool description 加 "[重要] 编辑文件前请先读取文件内容。"

**验证：** `mvnw compile` 编译通过

## T12-T13: 测试

**文件：** `PromptBuilderTest.java`, `SupplementMessageTest.java`
**验证：** `mvnw test` 全部通过

## 执行顺序

```
T1 ──→ T2 ──→ T8 ──→ T9 ──→ T10
  │                    │
  ├─→ T5 ──→ T6 ──────┤
  ├─→ T3 ──→ T8 ──────┤
  ├─→ T4 ──→ T7 ──────┤
  └─→ T11 (独立)

T2 ──→ T12 (测试)
T3 ──→ T13 (测试)
```
