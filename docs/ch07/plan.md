# MewCode 第7章 - 上下文管理 Plan

## 架构概览

新增 `context/` 包。ContextManager 插入 AgentLoop 的 LLM 调用前。
两层压缩：先检查单个工具结果大小（存盘替换），再估算总 token（摘要兜底）。

## 核心组件

### context/
- ContextManager.java — 暴露给 AgentLoop 的窄接口: prepare() / compress() / status()
- ContextConfig.java — 配置(threshold/margin/window/开关)
- TokenEstimator.java — 锚定 usage + 字符/4 估算
- ToolResultStorage.java — 第1层: 存盘+预览替换(逐字节稳定)
- ContextSummarizer.java — 第2层: LLM摘要(禁工具/先草稿/再正式/6区)
- BoundaryMessage.java — 压缩后边界消息

### 变更
- AgentLoop: 注入 ContextManager, 每轮 prepare() 调用, 捕获 prompt_too_long
- Repl: :compress 命令
- App: 创建 ContextManager

## 技术决策
| 决策点 | 选择 | 理由 |
|--------|------|------|
| Token估算 | 字符数/4 | 中英文折中 |
| 存盘格式 | .txt | 人可读，可重读 |
| 摘要调用 | 复用 LlmProvider(禁tools) | 不引新依赖 |
| 预览稳定性 | 固定格式+固定截断 | cache命中 |
