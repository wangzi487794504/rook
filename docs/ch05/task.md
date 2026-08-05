# MewCode 第5章 - 权限系统 Tasks

## T1: 创建 PermissionResult + PermissionMode + ToolCategory

**文件：** `permission/PermissionResult.java`, `permission/PermissionMode.java`
**依赖：** 无
**步骤：**
1. PermissionVerdict 枚举: ALLOW/DENY/ASK
2. PermissionResult record: verdict + reason + layer + 静态工厂
3. ToolCategory 枚举: READ/WRITE/EXEC
4. PermissionMode 枚举四档 + verdictFor(cat) 方法

## T2: 创建 RuleConfig

**文件：** `permission/RuleConfig.java`
**依赖：** 无
**步骤：** record: toolName, pattern(null=全匹配), allow, source

## T3: 创建 Blacklist

**文件：** `permission/Blacklist.java`
**依赖：** T1
**步骤：** 内置 6-8 条高危正则，check(ToolCall) → Deny 或穿透

## T4: 创建 PathSandbox

**文件：** `permission/PathSandbox.java`
**依赖：** T1
**步骤：** 构造接收 projectRoot，check(ToolCall) → 提取路径 → toRealPath → startsWith 检查

## T5: 创建 RuleEngine

**文件：** `permission/RuleEngine.java`
**依赖：** T1, T2
**步骤：** 加载三层 YAML(local>project>user)，合并规则，check(ToolCall) → glob 匹配

## T6: 创建 HumanInTheLoop

**文件：** `permission/HumanInTheLoop.java`
**依赖：** T1
**步骤：** ask(call, reason) → JLine 交互(↑↓/123) → Allow/Deny，永久允许写 rules.local.yaml

## T7: 创建 PermissionEngine

**文件：** `permission/PermissionEngine.java`
**依赖：** T1-T6
**步骤：** 串联五层 check(call) → 短路返回

## T8: 修改 ToolExecutor

**文件：** `tools/ToolExecutor.java`
**依赖：** T7
**步骤：** 注入 PermissionEngine，executeOne() 前调用 check()

## T9: 修改 AgentLoop

**文件：** `core/agent/AgentLoop.java`
**依赖：** T7
**步骤：** 识别 PERMISSION_DENIED 结果，不视为连续错误

## T10: 修改 Repl

**文件：** `cli/Repl.java`
**依赖：** T1, T6
**步骤：** 新增 :mode 命令，传递 LineReader 给 HumanInTheLoop

## T11: 修改 App

**文件：** `core/App.java`
**依赖：** T7
**步骤：** 创建 PermissionEngine(projectRoot)，传给 ToolExecutor

## T12-T15: 测试

**文件：** BlacklistTest / PathSandboxTest / RuleEngineTest / PermissionEngineTest
