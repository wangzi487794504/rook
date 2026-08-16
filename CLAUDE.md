# Rook

我正在构建一个终端 AI 编程助手（类似 Claude Code），项目名叫 Rook，使用 Java 实现。

## 语言
中文回答，中文注释。

## 技术栈
- Java 17 (OpenJDK Temurin)
- 构建工具：Maven（如未安装则使用 Maven Wrapper `mvnw`）
- LLM API：DeepSeekV4（兼容 Anthropic 协议）
- JSON 处理：Jackson
- HTTP 客户端：Java 11 `java.net.http.HttpClient`
- 测试框架：JUnit 5

## 代码规范

### 命名规范
- 类名：大驼峰（PascalCase），如 `ToolExecutor`
- 方法名/变量名：小驼峰（camelCase），如 `executeCommand`
- 常量：全大写下划线分隔（UPPER_SNAKE_CASE），如 `MAX_RETRY_COUNT`
- 包名：全小写，如 `com.rook.core`

### 注释规范
- 所有公共类和方法必须有 Javadoc 注释，使用中文
- 复杂逻辑处添加行内中文注释
- 每个类顶部注明 `@author` 和 `@since`

### 代码风格
- 缩进：4 空格，不使用 Tab
- 单行最大 120 字符
- 遵循标准 Java 编码规范
- 优先使用 `record` 定义不可变数据结构
- 使用 `Optional` 处理可能为 null 的返回值
- 异常处理：不允许吞异常，必须记录日志或向上抛

### 测试规范
- 测试类命名：`类名Test.java`
- 测试方法命名：`should_预期行为_When_条件`
- 单元测试覆盖所有公共方法
- 关键路径要求集成测试

## 项目结构

```
rook/
├── pom.xml                    — Maven 配置
├── CLAUDE.md                  — 项目指令
├── .claude/
│   └── skills/
│       └── mew-spec.md        — Spec 驱动开发 skill
├── docs/                      — Spec 文档
│   └── ch01/
│       ├── spec.md
│       ├── plan.md
│       ├── task.md
│       └── checklist.md
└── src/
    ├── main/java/com/rook/
    │   ├── core/              — 核心引擎
    │   ├── llm/               — LLM API 调用层
    │   ├── tools/             — 工具执行
    │   ├── prompt/            — Prompt 构建
    │   └── cli/               — CLI 入口
    └── test/java/com/rook/ — 测试代码
```

## 开发工作流

每次开发新功能/章节时，使用 `/mew-spec` skill：
1. 生成 spec.md → 审批
2. 生成 plan.md → 审批
3. 生成 task.md → 审批
4. 生成 checklist.md → 审批
5. 按 task.md 开发
6. 按 checklist.md 验收

## 测试

开发完功能后，用 tmux 做端到端测试：

1. 在 tmux 中启动 Rook
2. 输入一段真实的对话请求
3. 观察 Rook 是否正确调用工具、生成回复
4. 对照 checklist.md 逐项验收
