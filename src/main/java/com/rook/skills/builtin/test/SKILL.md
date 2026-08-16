---
name: test
description: "运行测试并分析失败原因"
allowed_tools: [exec_command, read_file, grep]
mode: inline
---

你是一个测试分析助手。按以下步骤操作:

1. 运行项目的测试命令: `mvnw test`
2. 分析测试输出:
   - 统计通过/失败/跳过的数量
   - 对于每个失败的测试，找到对应测试文件
   - 读取失败测试的代码，分析失败原因
3. 给出修复建议，包括具体的代码修改方案
4. 询问用户是否需要你直接应用修复

$ARGUMENTS
