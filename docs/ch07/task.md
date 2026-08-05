# MewCode 第7章 Tasks

## T1: ContextConfig
**文件:** `context/ContextConfig.java`
record: maxToolResultBytes(8192), contextWindow(200000), autoMargin(13000), manualMargin(3000), enabled(true)

## T2: BoundaryMessage
**文件:** `context/BoundaryMessage.java`
静态方法 postCompression() → ChatMessage

## T3: TokenEstimator
**文件:** `context/TokenEstimator.java`
estimate(messages, lastUsage, toolsDefSize) → int

## T4: ToolResultStorage (第1层)
**文件:** `context/ToolResultStorage.java`
maybeStore(call, result) → String; stablePreview(content) → String

## T5: ContextSummarizer (第2层)
**文件:** `context/ContextSummarizer.java`
summarize(messages, provider) → List<ChatMessage>

## T6: ContextManager
**文件:** `context/ContextManager.java`
prepare() / compress() / status() / emergencyCompress()

## T7: AgentLoop 集成
**文件:** `core/agent/AgentLoop.java`
注入 ContextManager, 每轮 prepare(), prompt_too_long 捕获

## T8: Repl :compress
**文件:** `cli/Repl.java`
:compress / :compress status

## T9: App 创建 ContextManager
**文件:** `core/App.java`

## T10-11: 测试
TokenEstimatorTest / ToolResultStorageTest
