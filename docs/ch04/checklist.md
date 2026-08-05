# MewCode 第4章 - 结构化 System Prompt Checklist

## 实现完整性
- [ ] PromptContext + PromptModule 接口已定义（验证：编译通过）
- [ ] PromptBuilder 可按优先级装配模块（验证：`PromptBuilderTest`）
- [ ] SupplementMessage 可创建带标签的消息（验证：`SupplementMessageTest`）
- [ ] Usage 包含缓存字段（验证：编译通过）
- [ ] 七个固定模块已实现（验证：编译通过）
- [ ] 三个可选模块已实现（验证：编译通过）
- [ ] AnthropicProvider 解析缓存 usage（验证：调用 API 后 lastUsage() 缓存字段非负）
- [ ] AgentLoop 注入 System Prompt + 频率控制（验证：编译通过）
- [ ] Repl 移除 injectSystemPrompt（验证：编译通过）
- [ ] 工具描述双重强化（验证：EditFileTool.description() 包含"[重要]"）

## 验收标准对照

### 模块化构建
- [ ] AC1: Builder 输出所有模块且按优先级排列（验证：`PromptBuilderTest`）
- [ ] AC2: 新增模块不改 Builder 代码直接注册（验证：`PromptBuilderTest`）

### 缓存感知
- [ ] AC3: 稳定模块在 messages[0] 的 system 角色
- [ ] AC4: Usage 含 cache 字段（验证：`Usage.ZERO.cacheReadInputTokens() == 0`）
- [ ] AC5: 调用 API 后缓存字段非负（验证：交互中观察）

### 补充消息
- [ ] AC6: [Supplement] 消息不导致程序当作用户轮次
- [ ] AC7: Plan Mode 切换后可见 [Supplement] 标记

### 注入频率
- [ ] AC8: 首轮完整 + 间隔3轮完整 + 其余精简
- [ ] AC9: 非 Plan Mode 不注入模式提示

### 双重强化
- [ ] AC10: EditFileTool description 含关键规则
- [ ] AC11: ToolUsageModule render() 含相同规则

### 缓存验证
- [ ] AC12: 解析 Anthropic message_start 中的 cache_* 字段
- [ ] AC13: 多轮后缓存 token 合理增长

## 编译与测试
- [ ] 编译无错误（验证：`mvnw compile`）
- [ ] 全部测试通过（验证：`mvnw test`）

## 端到端场景
1. 正常模式下发消息 → System Prompt 包含七个模块 + 工具描述
2. `:plan` 切换 → 首轮完整 Plan 提示，第2轮精简，第4轮完整
3. `:do` 切换回 → Plan 提示消失
4. 工具调用 → EditFileTool 描述含"[重要]"
