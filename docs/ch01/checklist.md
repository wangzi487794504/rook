# MewCode 第1章 - 对话交互 Checklist

> 每一项通过运行代码或观察行为来验证，聚焦系统行为。

## 实现完整性
- [ ] ChatMessage 和 StreamCallback 已定义（验证：编译通过）
- [ ] ProviderConfig 和 AppConfig 已定义（验证：编译通过）
- [ ] ConfigLoader 能读取 YAML 并校验字段（验证：`mvnw test -Dtest=ConfigLoaderTest` 通过）
- [ ] LlmProvider 接口已定义（验证：编译通过）
- [ ] Session 能增删消息并持久化（验证：`mvnw test -Dtest=SessionTest` 通过）
- [ ] AnthropicProvider 已实现（验证：编译通过）
- [ ] OpenAiProvider 已实现（验证：编译通过）
- [ ] ProviderFactory 能根据 protocol 创建对应实例（验证：`mvnw test -Dtest=ProviderFactoryTest` 通过）
- [ ] Repl 已实现，包含所有命令（验证：编译通过）
- [ ] App 启动协调已实现（验证：编译通过）
- [ ] Main 入口已适配（验证：编译通过）

## 验收标准对照

### 配置管理
- [ ] AC1: `config.yaml` 不存在或格式错误时打印明确错误信息并退出（验证：删除 config.yaml 后启动，观察报错信息是否清晰）
- [ ] AC2: 配置文件可定义多个 Provider，每个字段缺失时均有错误提示（验证：创建一个缺 api_key 的配置，观察是否提示具体缺失字段）

### CLI 交互
- [ ] AC3: 启动后显示 `>` 提示符（验证：`bash mvnw exec:java` 启动，观察显示 `>` 等待输入）
- [ ] AC4: 输入 `:help` 显示所有命令及说明（验证：在 REPL 中输入 `:help`，列出 `:exit` `:switch` `:model` `:clear` 及说明）
- [ ] AC5: 输入 `:exit` 正常退出（验证：输入 `:exit`，程序正常退出回到 shell）
- [ ] AC6: ↑ ↓ 可回溯历史输入（验证：在 REPL 中输入两条消息，按 ↑ 看到上一条，按 ↓ 回到下一条）

### Provider
- [ ] AC7: Anthropic 协议 Provider 流式回复（验证：配置 deepseek，输入"你好"，观察逐字打印回复）
- [ ] AC8: OpenAI 协议 Provider 流式回复（验证：`:switch` 到 OpenAI provider，输入"你好"，观察逐字打印回复）
- [ ] AC9: `:switch <名称>` 切换 Provider 生效（验证：`:switch <name>` 后，`:model` 确认已切换，再发消息验证用的是新 provider）

### 对话
- [ ] AC10: 打字机效果逐字打印 AI 回复（验证：输入"你好"，观察回复是否逐字出现而非一次性输出）
- [ ] AC11: 多轮对话记忆（验证：连续发三条消息，第三条消息检查 AI 引用了前两条内容）
- [ ] AC12: 会话持久化恢复（验证：`:exit` 后重新启动，发一条"继续刚才的话题"，AI 能接上之前的上下文）

### 健壮性
- [ ] AC13: 错误 API Key 不崩溃（验证：配置错误的 api_key，发消息后提示"认证失败"而非崩溃）
- [ ] AC14: 网络不可达不崩溃（验证：配置不存在的 base_url，发消息后提示"网络连接失败"而非崩溃）

## 编译与测试
- [ ] 项目编译无错误（验证：`bash mvnw compile`）
- [ ] 全部测试通过（验证：`bash mvnw test`）
- [ ] 无编译警告（验证：`bash mvnw compile -Xlint:all`）

## 端到端场景

### 场景 1：第一次启动 → 对话 → 存盘 → 恢复

```
1. 启动 MewCode（第一次，无 ~/.mewcode/sessions/）
2. 看到 > 提示符
3. 输入「我叫小明」
4. 看到 AI 逐字回复
5. 输入「我叫什么名字？」
6. AI 回复包含"小明"
7. 输入 :exit
8. 检查 ~/.mewcode/sessions/ 下已生成 JSON 文件
9. 重新启动 MewCode
10. 输入「上次我说我叫什么？」
11. AI 回复包含"小明"（证明恢复成功）
```

### 场景 2：切换 Provider

```
1. 确认 config.yaml 配置了两个 provider：deepseek 和 gpt
2. 启动 MewCode
3. 输入 :model → 显示当前为 deepseek
4. 输入「你好」→ 收到回复
5. 输入 :switch gpt → 提示已切换
6. 输入 :model → 显示当前为 gpt
7. 输入「你好」→ 收到回复（来自 gpt）
```

### 场景 3：异常处理

```
1. 删除 config.yaml → 启动 → 看到明确错误提示
2. 修改 config.yaml 的 api_key 为错误值 → 启动 → 看到 > → 输入消息 → 看到认证失败提示
3. 修改 base_url 为 http://localhost:99999 → 启动 → 看到 > → 输入消息 → 看到网络连接失败提示
```

### 场景 4：交互细节

```
1. 启动 → 看到 > 提示符
2. 直接按回车 → 仍然是 > 等待输入
3. 输入消息1 → AI 逐字回复
4. 输入消息2 → AI 逐字回复
5. 按 ↑ → 显示消息2
6. 按 ↑ 再按 ↑ → 显示消息1
7. 输入 :clear → 当前对话清空
8. 输入「还记得我说过什么吗？」 → AI 不记得（会话已清空）
```
