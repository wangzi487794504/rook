---
title: "checklist"
author: wangzijie
date: 2026-08-05
---

# MewCode 第5章 - 权限系统 Checklist

- [ ] AC1: rm -rf / → 黑名单拦截
- [ ] AC2: bypass Permissions 下黑名单仍拦截
- [ ] AC3: /etc/passwd → 沙箱拦截
- [ ] AC4: symlink 逃逸 → 沙箱拦截
- [ ] AC5: Bash(git *) allow → git status 放行
- [ ] AC6: WriteFile(*.class) deny → 被拦
- [ ] AC7: 本地级规则覆盖项目级
- [ ] AC8: plan 模式只能读
- [ ] AC9: default 模式写 Ask
- [ ] AC10: bypass Permissions 全 Allow
- [ ] AC11: default 写文件 → 用户确认 → 执行
- [ ] AC12: 永久允许 → 写入 rules.local.yaml
- [ ] AC13: 拒绝本次 → 模型继续
- [ ] AC14: Deny 后 Agent Loop 继续
- [ ] 编译无错误 | 全部测试通过
