# MewCode 第5章 - 权限系统 Spec

## 背景

ch04 完成后，MewCode 拥有了结构化 System Prompt。但工具执行仍然是"收到即执行"——
没有权限控制。模型可以读写任意文件、执行任意命令，没有任何约束。

这在真实项目中风险极高：模型可能误删 `src/` 目录、执行 `rm -rf /`、修改项目外的系统配置。

本章给 MewCode 装上五层防御的权限系统，在不影响 Agent Loop 功能的前提下对工具执行进行全面控制。

## 目标

- 黑名单硬拦截高危命令（不可配置绕过）
- 沙箱限制文件读写到项目目录内，解析符号链接防逃逸
- 三层 YAML 规则文件（用户级/项目级/本地级），靠近项目优先级越高
- 四种权限模式（default / acceptEdits / plan / bypassPermissions）整体切换
- 人在回路：规则未命中时询问用户，支持三种放行范围（本次/本会话/永久）
- 权限拒绝不终止 Agent Loop，以结构化错误让模型调整策略

## 功能需求

### 五层防御架构
- F1: 五层串联、逐层短路。一次工具调用依次经过 黑名单 → 沙箱 → 规则引擎 → 模式兜底 → 人在回路。任一层给出终值（Allow/Deny）即短路返回；全部未拦且模式判 Ask 时落到人在回路
- F2: 判定只有三种中间值：Allow / Deny / Ask。模式兜底层的值域严格为 {Allow, Ask}，Deny 只可能来自黑名单、沙箱、显式 deny 规则、人在回路拒绝

### 第一层：黑名单
- F3: 用一组内置正则拦截高危命令（仅对 exec_command 生效）
- F4: 黑名单不可被任何配置或模式放开
- F5: 拦截项包括但不限于：rm -rf /、mkfs、dd if=、> /dev/sda、chmod 777 / 等

### 第二层：路径沙箱
- F6: 限制文件类工具（read_file、write_file、edit_file、glob、grep）读写范围在项目根目录内
- F7: 先解析符号链接，再做前缀判断，防止 .. 和 symlink 逃逸
- F8: 沙箱获取项目根目录（启动时传入），该目录内路径放行

### 第三层：规则引擎
- F9: 规则格式为 `工具名(模式)` 或 `工具名`，每条规则结果只有 allow 或 deny
- F10: 模式支持精确匹配和 glob 匹配
- F11: 先匹配工具名，再匹配模式

### 三级规则文件
- F12: 规则按三层 YAML 存放：用户级 ~/.mewcode/rules.yaml、项目级 .mewcode/rules.yaml、本地级 .mewcode/rules.local.yaml
- F13: 合并策略：本地级 > 项目级 > 用户级

### 第四层：权限模式
- F14: 四档全局模式：

| 模式 | 读类工具 | 文件写类工具 | 命令执行类工具 |
|------|---------|------------|---------------|
| default | Allow | Ask | Ask |
| acceptEdits | Allow | Allow | Ask |
| plan | Allow | Deny | Deny |
| bypassPermissions | Allow | Allow | Allow |

- F15: 模式兜底值域 {Allow, Ask}

### 第五层：人在回路
- F16: 模式判 Ask 时暂停 Agent Loop，弹出确认提示
- F17: ↑↓ 移动 + 回车，数字键 1/2/3 直选，默认高亮「允许本次」
- F18: 三种选择：允许本次、拒绝本次、永久允许（写入 rules.local.yaml）
- F19: 拒绝时返回 PERMISSION_DENIED，Agent Loop 不中断

### 跨协议一致
- F20: 权限判定在编排层，与 Provider 无关

## 非功能需求

- N1: 权限校验耗时 < 5ms（不含用户等待）
- N2: 规则加载失败不崩溃
- N3: 等待确认期间不丢事件
- N4: 黑名单和沙箱在任何模式下不可绕过

## 不做的事（本章明确排除）

- 网络请求限制
- 资源配额
- 审计日志
- 权限策略远程同步
- GUI 确认

## 验收标准

### 黑名单
- AC1: 执行 rm -rf / → 被黑名单拦截
- AC2: bypass Permissions 模式下仍被黑名单拦截

### 路径沙箱
- AC3: 读 /etc/passwd → 沙箱拦截
- AC4: symlink 逃逸 → 沙箱拦截

### 规则引擎
- AC5: Bash(git *) allow → git status 放行
- AC6: WriteFile(*.class) deny → Test.class 被拦
- AC7: 本地级规则覆盖项目级

### 权限模式
- AC8: plan 模式只能读
- AC9: default 模式写 Ask
- AC10: bypass Permissions 全 Allow（但黑名单/沙箱/deny 规则仍生效）

### 人在回路
- AC11: default 模式写文件 → 弹出确认 → 允许本次 → 执行
- AC12: 永久允许 → 写入 rules.local.yaml
- AC13: 拒绝本次 → 模型继续 Loop

### 拒绝不中断
- AC14: Deny 后 Agent Loop 继续

### 跨协议
- AC15: Anthropic/OpenAI 行为一致
