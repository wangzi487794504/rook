# MewCode 第2章 - 工具系统 Checklist

> 每一项通过运行代码或观察行为来验证，聚焦系统行为。

## 实现完整性
- [ ] Tool 接口、ToolResult、ToolCall 已定义（验证：编译通过）
- [ ] ToolRegistry 可注册、查找、输出 API 格式（验证：`mvnw test -Dtest=ToolRegistryTest`）
- [ ] 六个工具均已实现（验证：编译通过）
- [ ] StreamCallback 新增 onToolCall（验证：编译通过）
- [ ] LlmProvider.stream() 支持 ToolRegistry 参数（验证：编译通过）
- [ ] AnthropicProvider 发送带 tools 的请求（验证：测试验证请求体 JSON 包含 tools 字段）
- [ ] OpenAiProvider 发送带 tools 的请求（验证：测试验证请求体 JSON 包含 tools 字段）
- [ ] App 创建 ToolRegistry 并注入 Repl（验证：编译通过）
- [ ] Repl 实现工具调度链路（验证：编译通过）

## 验收标准对照

### Tool 接口与注册
- [ ] AC1: 六个工具均已注册，通过名称可查找（验证：`ToolRegistryTest` 遍历六种名称均不为 null）
- [ ] AC2: toToolDefinitions() 输出格式符合 Anthropic API（验证：检查输出 JSON 包含 name/description/input_schema）

### 六个核心工具
- [ ] AC3: 读文件 — 存在文件返回内容，不存在返回错误（验证：`ReadFileToolTest`）
- [ ] AC4: 写文件 — 写入后再次读取内容一致（验证：`WriteFileToolTest`）
- [ ] AC5: 改文件 — 唯一匹配替换成功；0次报未找到；多次报匹配N处（验证：`EditFileToolTest`）
- [ ] AC6: 命令执行 — `echo hello` 输出 hello + exitCode=0；超时命令返回超时错误（验证：`ExecCommandToolTest`）
- [ ] AC7: Glob — 给定模式返回匹配文件列表（验证：`GlobToolTest`）
- [ ] AC8: Grep — 搜索文本返回文件:行号:内容（验证：`GrepToolTest`）

### Tool 执行
- [ ] AC9: 超时 30s 自动终止返回超时错误（验证：执行 `sleep 35`，应返回超时而非挂起）
- [ ] AC10: 结果超 100KB 自动截断，标注"已截断"（验证：读取 >100KB 文件，输出末尾有截断标记）

### LLM 协议适配
- [ ] AC11: AnthropicProvider 请求体包含 tools 定义（验证：在 Repl 中发消息，确认请求体含 tools）
- [ ] AC12: SSE 中 tool_use JSON 参数碎片被正确拼接（验证：发"读 pom.xml"，模型返回 tool_use 参数完整）
- [ ] AC13: tool_result 回灌后模型能生成最终文本回复（验证：工具执行后，模型给出了对结果的解读）

### 交互流程
- [ ] AC14: 发"帮我读一下 pom.xml"，模型调 read_file，自动执行，回结果，生成解读（验证：端到端走一遍）
- [ ] AC15: 仅单次工具调用，不循环（验证：工具执行完 → 模型给最终回复 → 回到 > 提示符）

## 编译与测试
- [ ] 项目编译无错误（验证：`mvnw compile`）
- [ ] 全部测试通过（验证：`mvnw test`）
- [ ] 无编译警告（验证：`mvnw compile -Xlint:all`）

## 端到端场景

### 场景 1：读文件工具
```
1. 启动 MewCode
2. 输入「帮我读一下 config.yaml 文件的内容」
3. 看到 [调用工具: read_file 参数: {filePath: config.yaml}]
4. 工具返回文件完整内容
5. AI 对文件内容进行解读回复
```

### 场景 2：改文件工具
```
1. 输入「把 pom.xml 里的 JLine 3.26.3 改成 3.27.0」
2. 看到编辑工具被调用，返回成功
3. 用读文件工具验证 pom.xml 已修改
4. (手动改回去)
```

### 场景 3：命令执行工具
```
1. 输入「帮我运行 mvnw compile」
2. 看到 [调用工具: exec_command ...]
3. 看到 BUILD SUCCESS 结果
4. AI 解读编译结果
```

### 场景 4：搜索代码
```
1. 输入「项目中所有 pom.xml 文件在哪里」
2. AI 调用 glob 工具，pattern=**/pom.xml
3. 返回找到的文件列表
```

### 场景 5：错误处理
```
1. 输入「帮我读不存在的文件 xyz.txt」
2. AI 调用 read_file
3. 工具返回错误「文件不存在」
4. AI 回复告诉用户文件不存在，不会崩溃
```

### 场景 6：改文件匹配失败
```
1. 输入「把 pom.xml 第一行改成 <project>」
2. 如果第一行不是 <project>，EditFileTool 返回 0 次匹配
3. AI 收到错误，告诉用户原文片段未找到
```
