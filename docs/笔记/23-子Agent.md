# 23 — 子 Agent：委派与协作

## 这一章做什么

让主 Agent 用 `agent` 工具派生子 Agent，分工协作完成复杂任务。

**两种模式：**

- **defined 模式**：指定 `subagent_type`，从空白对话 + 角色 prompt 启动（专业角色）
- **fork 模式**：不指定 type，继承父历史和工具集，首次请求命中缓存（分身继续干）

**完成后你能做到：**

- "派一个 explore Agent 帮我扫一下代码库" → 只读探索，不碰文件
- "派一个 review Agent 审查这个 PR" → 专业审查角色
- "fork 一个 Agent 去查这个 bug" → 继承上下文，并行排查
- 大任务拆成小任务，多个子 Agent 并行干

## 为什么需要

### 单个 Agent 的局限

```
用户：帮我重构这个模块，同时审查一下代码，再写个测试

单个 Agent：
  1. 先重构代码（改了一堆文件）
  2. 再审查（但自己改的代码，审查不客观）
  3. 再写测试（上下文已经很长了，容易忘前面的）
```

**问题：**

|    问题    |                      说明                       |
| :--------: | :---------------------------------------------: |
| 上下文污染 |   重构的代码和审查的需求混在一起，AI 容易混乱   |
|   不客观   |       自己写的代码自己审查，发现不了问题        |
|  串行低效  |              一件一件做，不能并行               |
|  角色混乱  | 一会儿是开发者，一会儿是审查者，prompt 互相干扰 |
|   风险大   |       改代码和读代码混在一起，容易误操作        |

### 子 Agent 的优势

```
主 Agent：
  ├─ 派 refactor Agent → 重构代码（有写权限）
  ├─ 派 review Agent   → 审查代码（只读，客观）
  └─ 派 test Agent     → 写测试（独立上下文）
```

**好处：**

- 角色分离：每个子 Agent 有明确的角色和工具范围
- 上下文隔离：各自的对话历史不混
- 可并行：多个子 Agent 同时干活
- 安全：探索 Agent 只读，不会改坏东西
- 专业：每个角色有专门的 prompt，更专业

## 核心概念

### defined 模式

- 指定 `subagent_type`（如 `explore`、`review`）
- 从空白对话启动，加载角色定义的 prompt
- 不继承父历史（干净的上下文）
- 有自己的工具白名单和权限模式
- 适合：专业角色任务（审查、测试、探索）

### fork 模式

- 不指定 `subagent_type`
- 继承父 Agent 的历史消息和工具集
- 首次 LLM 请求命中 prompt cache（快、省 token）
- 适合：从当前状态分叉出去干一件事

### 角色定义

- `agent.md` 文件（YAML frontmatter + Markdown 正文）
- 三级加载：项目 > 用户 > 内置
- 定义：名字、描述、工具、禁用工具、最大轮数、模型、权限模式

### 全局禁止

- 子 Agent 不能用 `agent` 工具（防无限嵌套）
- `globalDisallowed = ["agent"]`，不管角色定义怎么写都禁用

------

## 第一步：角色定义 agent.md

### 文件格式

```
---
name: explore
description: "只读探索Agent，快速扫描代码库结构和关键文件"
tools: [read_file, glob, grep]
disallowedTools: [agent, write_file, edit_file, exec_command]
maxRounds: 10
model: haiku
permissionMode: plan
---

你是 Rook 的代码探索子 Agent。你的任务是快速了解代码库结构，
**只读不写**。

## 工作方式
1. 先用 glob 查看目录结构
2. 用 grep 搜索关键类和方法
3. 用 read_file 阅读核心文件
4. 最后给出结构化的总结

## 输出格式
- 项目结构概览
- 核心模块说明
- 关键类和方法
- 技术栈

## 注意事项
- 不要修改任何文件
- 不要执行命令
- 只做信息收集和分析
```

### frontmatter 字段详解

|       字段        | 必填 |                     含义                     |         示例          |
| :---------------: | :--: | :------------------------------------------: | :-------------------: |
|      `name`       |  ✅   |                 角色唯一标识                 |       `explore`       |
|   `description`   |  ✅   |              描述，用于工具列表              |   "只读探索 Agent"    |
|      `tools`      |  ❌   |          工具白名单，不填则全部可用          |  `[read_file, glob]`  |
| `disallowedTools` |  ❌   |         禁用工具（优先级高于白名单）         | `[agent, write_file]` |
|    `maxRounds`    |  ❌   |        最大 Agent Loop 轮数，默认 15         |         `10`          |
|      `model`      |  ❌   |       使用的模型（如 haiku 快速便宜）        |        `haiku`        |
| `permissionMode`  |  ❌   | 权限模式（default/accept_edits/plan/bypass） |        `plan`         |

### 内置角色示例

#### 1. explore — 只读探索

```
---
name: explore
description: "只读探索Agent，快速扫描代码库"
tools: [read_file, glob, grep]
disallowedTools: [agent, write_file, edit_file, exec_command]
maxRounds: 10
model: haiku
permissionMode: plan
---
你是代码探索专家，只读不写，快速了解项目结构...
```

**特点：**

- 只能读文件、搜索、glob
- 不能写、不能执行命令
- 用 haiku 模型（快、便宜）
- plan 权限模式（禁止写）

#### 2. review — 代码审查

```
---
name: review
description: "代码审查Agent，分析变更并给出建议"
tools: [read_file, glob, grep, exec_command]
disallowedTools: [agent, write_file, edit_file]
maxRounds: 15
permissionMode: plan
---
你是资深代码审查专家，客观分析代码质量...
```

**特点：**

- 可以执行 `git diff` 等只读命令
- 不能改文件
- 客观审查（独立上下文，没参与写代码）

#### 3. test — 测试工程师

```
---
name: test
description: "测试Agent，编写和运行测试"
tools: [read_file, write_file, edit_file, exec_command, glob, grep]
disallowedTools: [agent]
maxRounds: 20
permissionMode: accept_edits
---
你是测试工程师，负责编写和运行测试...
```

**特点：**

- 可以写测试文件
- 可以运行测试命令
- accept_edits 权限（允许写文件）

------

## 第二步：角色加载器 — 三级加载

```
// subagent/AgentDefLoader.java
package com.rook.subagent;

import org.yaml.snakeyaml.Yaml;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class AgentDefLoader {

    /**
     * 三级加载：内置 → 用户 → 项目
     * 优先级从低到高，后者覆盖前者
     */
    public static Map<String, AgentDef> loadAll(
            Path builtinDir,
            Path userDir,
            Path projectDir) throws IOException {
        
        Map<String, AgentDef> result = new LinkedHashMap<>();

        // 内置（最低优先级）
        if (builtinDir != null && Files.exists(builtinDir)) {
            scan(builtinDir).forEach(d -> result.put(d.name(), d));
        }
        // 用户（覆盖内置）
        if (userDir != null && Files.exists(userDir)) {
            scan(userDir).forEach(d -> result.put(d.name(), d));
        }
        // 项目（最高优先级）
        if (projectDir != null && Files.exists(projectDir)) {
            scan(projectDir).forEach(d -> result.put(d.name(), d));
        }

        return result;
    }

    /**
     * 扫描目录下的 agent.md
     * 每个子目录一个角色，里面有 agent.md
     */
    public static List<AgentDef> scan(Path dir) throws IOException {
        List<AgentDef> defs = new ArrayList<>();
        if (!Files.exists(dir)) return defs;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    Path defFile = entry.resolve("agent.md");
                    if (Files.exists(defFile)) {
                        try {
                            defs.add(parse(defFile));
                        } catch (Exception e) {
                            System.err.println("[SubAgent] 角色加载失败: " 
                                + defFile + " - " + e.getMessage());
                        }
                    }
                }
            }
        }
        return defs;
    }

    /**
     * 解析单个 agent.md
     */
    public static AgentDef parse(Path file) throws IOException {
        String content = Files.readString(file);

        // 分割 frontmatter 和正文
        int firstSep = content.indexOf("---");
        int secondSep = content.indexOf("---", 3);
        if (firstSep != 0 || secondSep < 0) {
            throw new IllegalArgumentException("agent.md 格式错误");
        }

        String yamlPart = content.substring(3, secondSep).trim();
        String body = content.substring(secondSep + 3).trim();

        Map<String, Object> fm = new Yaml().load(yamlPart);

        String name = (String) fm.get("name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("缺少 name 字段");
        }

        String description = (String) fm.getOrDefault("description", "");
        
        @SuppressWarnings("unchecked")
        List<String> tools = (List<String>) fm.getOrDefault("tools", List.of());
        
        @SuppressWarnings("unchecked")
        List<String> disallowedTools = (List<String>) fm.getOrDefault(
            "disallowedTools", List.of());

        int maxRounds = ((Number) fm.getOrDefault("maxRounds", 15)).intValue();
        String model = (String) fm.get("model");
        String permissionMode = (String) fm.getOrDefault("permissionMode", "default");

        return new AgentDef(
            name, description, tools, disallowedTools,
            maxRounds, model, permissionMode, body, file
        );
    }
}
```

### 逐段详解

#### 1. 三级覆盖

和 Skill 系统一样的三级加载：

- 内置：随程序发布的标准角色
- 用户：`~/.rook/agents/`，个人自定义
- 项目：`./.rook/agents/`，项目共享

**项目级最高，可以覆盖内置角色。**

#### 2. 目录结构

```
.rook/agents/
├── explore/
│   └── agent.md
├── review/
│   └── agent.md
└── test/
    └── agent.md
```

每个角色一个子目录，里面放 `agent.md`。

------

## 第三步：AgentDef — 角色数据结构

```
// subagent/AgentDef.java
package com.rook.subagent;

import java.util.List;

public record AgentDef(
    String name,              // 角色名
    String description,       // 描述
    List<String> tools,       // 工具白名单
    List<String> disallowedTools, // 禁用工具
    int maxRounds,            // 最大轮数
    String model,             // 使用的模型
    String permissionMode,    // 权限模式
    String prompt,            // 角色 prompt（正文）
    java.nio.file.Path sourceFile
) {
    /**
     * 获取最终可用工具列表
     * 白名单 - 禁用工具 - 全局禁止
     */
    public List<String> effectiveTools(List<String> allTools, 
                                        List<String> globalDisallowed) {
        List<String> allowed = tools.isEmpty() ? allTools : tools;
        
        return allowed.stream()
            .filter(t -> !disallowedTools.contains(t))
            .filter(t -> !globalDisallowed.contains(t))
            .toList();
    }
}
```

### 工具过滤逻辑

```
所有工具
  ├─ 白名单过滤（如果定义了 tools）
  ├─ 禁用工具过滤（disallowedTools）
  └─ 全局禁止过滤（globalDisallowed = ["agent"]）
      ↓
  子 Agent 最终可用工具
```

**三层过滤：**

1. 白名单：只允许这些工具（不定义则全部允许）
2. 角色禁用：角色定义里明确禁止的
3. 全局禁止：所有子 Agent 都不能用的（agent 工具）

------

## 第四步：AgentTool — 子 Agent 工具

```
// subagent/AgentTool.java
package com.rook.subagent;

import com.rook.tools.Tool;
import com.rook.tools.ToolResult;
import java.util.*;

public class AgentTool implements Tool {

    private final Map<String, AgentDef> agentDefs;
    private final SubAgentRunner runner;
    private final List<ChatMessage> parentHistory;
    private final LlmProvider parentProvider;
    private final ToolRegistry parentTools;
    private final PermissionEngine parentPermission;

    // 全局禁止：所有子 Agent 都不能用这些工具
    private static final List<String> GLOBAL_DISALLOWED = List.of("agent");

    public AgentTool(
            Map<String, AgentDef> agentDefs,
            SubAgentRunner runner,
            List<ChatMessage> parentHistory,
            LlmProvider parentProvider,
            ToolRegistry parentTools,
            PermissionEngine parentPermission) {
        this.agentDefs = agentDefs;
        this.runner = runner;
        this.parentHistory = parentHistory;
        this.parentProvider = parentProvider;
        this.parentTools = parentTools;
        this.parentPermission = parentPermission;
    }

    @Override
    public String name() {
        return "agent";
    }

    @Override
    public String description() {
        StringBuilder sb = new StringBuilder();
        sb.append("派生子 Agent 执行独立任务。\n");
        sb.append("两种模式：\n");
        sb.append("- defined: 指定 subagent_type，使用预定义角色\n");
        sb.append("- fork: 不指定 type，继承当前上下文\n\n");
        sb.append("可用角色：\n");
        for (AgentDef def : agentDefs.values()) {
            sb.append("- ").append(def.name()).append(": ")
              .append(def.description()).append("\n");
        }
        return sb.toString();
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "subagent_type", Map.of(
                    "type", "string",
                    "description", "子 Agent 角色类型（defined 模式）"
                ),
                "prompt", Map.of(
                    "type", "string",
                    "description", "给子 Agent 的任务描述"
                )
            ),
            "required", List.of("prompt")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String agentType = (String) params.get("subagent_type");
        String task = (String) params.get("prompt");

        if (task == null || task.isBlank()) {
            return ToolResult.error("prompt 不能为空");
        }

        // 判断模式
        String mode = (agentType != null && !agentType.isBlank()) ? "defined" : "fork";

        try {
            // 运行子 Agent（同步等待完成）
            SubAgentResult result = runner.run(
                mode,
                agentType,
                task,
                parentHistory,
                parentProvider,
                parentTools,
                parentPermission,
                agentDefs,
                GLOBAL_DISALLOWED
            );

            return ToolResult.ok(formatResult(result));

        } catch (Exception e) {
            return ToolResult.error("子 Agent 执行失败: " + e.getMessage());
        }
    }

    private String formatResult(SubAgentResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("【子 Agent 执行结果】\n");
        sb.append("模式: ").append(result.mode()).append("\n");
        sb.append("角色: ").append(result.agentType() != null ? result.agentType() : "fork").append("\n");
        sb.append("轮数: ").append(result.rounds()).append("\n");
        sb.append("工具调用: ").append(result.toolCalls()).append(" 次\n\n");
        sb.append("--- 输出 ---\n");
        sb.append(result.output());
        return sb.toString();
    }
}
```

### 逐段详解

#### 1. description 里列出可用角色

```
@Override
public String description() {
    StringBuilder sb = new StringBuilder();
    sb.append("派生子 Agent 执行独立任务。\n");
    sb.append("两种模式：...\n\n");
    sb.append("可用角色：\n");
    for (AgentDef def : agentDefs.values()) {
        sb.append("- ").append(def.name()).append(": ")
          .append(def.description()).append("\n");
    }
    return sb.toString();
}
```

**★ 工具 description 是 AI 决定怎么调用的关键。**

把可用角色列在 description 里，AI 才知道有哪些角色可以派。

比如 AI 看到 "explore: 只读探索 Agent"，就知道要探索代码时派这个。

#### 2. 模式判断

```
String mode = (agentType != null && !agentType.isBlank()) ? "defined" : "fork";
```

- 传了 `subagent_type` → defined 模式（用预定义角色）
- 没传 → fork 模式（继承父上下文）

#### 3. 同步执行

```
SubAgentResult result = runner.run(...);
return ToolResult.ok(formatResult(result));
```

**当前实现是同步的：** 主 Agent 等子 Agent 跑完，拿到结果再继续。

**后续可以扩展为异步：**

- 派出去就返回 "任务已启动"
- 主 Agent 继续干别的
- 子 Agent 完成后通知主 Agent

但同步更简单，先实现同步版。

------

## 第五步：SubAgent — 执行引擎

```
// subagent/SubAgentRunner.java
package com.rook.subagent;

import com.rook.core.agent.*;
import com.rook.llm.*;
import com.rook.tools.*;
import com.rook.permission.*;
import java.util.*;

public class SubAgentRunner {

    public SubAgentResult run(
            String mode,
            String agentType,
            String task,
            List<ChatMessage> parentHistory,
            LlmProvider parentProvider,
            ToolRegistry parentTools,
            PermissionEngine parentPermission,
            Map<String, AgentDef> agentDefs,
            List<String> globalDisallowed) throws Exception {

        // ── 1. 准备角色和配置 ──
        AgentDef def = null;
        if ("defined".equals(mode)) {
            def = agentDefs.get(agentType);
            if (def == null) {
                throw new IllegalArgumentException("未知角色: " + agentType);
            }
        }

        // ── 2. 准备历史消息 ──
        List<ChatMessage> history = new ArrayList<>();

        if ("defined".equals(mode)) {
            // defined 模式：角色 prompt + 任务（空白上下文）
            history.add(new ChatMessage("system", def.prompt()));
            history.add(new ChatMessage("user", task));
        } else {
            // fork 模式：继承父历史 + 追加任务
            history.addAll(parentHistory);
            history.add(new ChatMessage("user", 
                "【这是一个 fork 出的子 Agent，请独立完成以下任务】\n\n" + task));
        }

        // ── 3. 准备工具（过滤） ──
        ToolRegistry subTools = parentTools.clone();
        if ("defined".equals(mode)) {
            List<String> allowed = def.effectiveTools(
                parentTools.allNames(), globalDisallowed);
            subTools.setWhitelist(allowed);
        } else {
            // fork 模式：继承父工具，但去掉 agent
            subTools.setBlacklist(globalDisallowed);
        }

        // ── 4. 准备 Provider（可能用不同模型） ──
        LlmProvider provider = parentProvider;
        if ("defined".equals(mode) && def.model() != null) {
            // 切换到角色指定的模型（如 haiku）
            provider = parentProvider.withModel(def.model());
        }

        // ── 5. 准备权限引擎 ──
        PermissionEngine permission = parentPermission;
        if ("defined".equals(mode) && def.permissionMode() != null) {
            permission = parentPermission.withMode(def.permissionMode());
        }

        // ── 6. 准备 LoopConfig ──
        int maxRounds = "defined".equals(mode) ? def.maxRounds() : 15;
        LoopConfig config = new LoopConfig(maxRounds, 5, 3);

        // ── 7. 运行 Agent Loop ──
        AgentLoop loop = new AgentLoop(provider, subTools, permission, config);
        AgentEvent result = loop.runToCompletion(history);

        // ── 8. 返回结果 ──
        return new SubAgentResult(
            mode,
            agentType,
            result.output(),
            loop.getIteration(),
            loop.getToolCallCount(),
            history
        );
    }
}

// 子 Agent 执行结果
public record SubAgentResult(
    String mode,
    String agentType,
    String output,
    int rounds,
    int toolCalls,
    List<ChatMessage> history
) {}
```

### 逐段详解

#### 1. defined 模式 — 空白上下文

```
if ("defined".equals(mode)) {
    history.add(new ChatMessage("system", def.prompt()));
    history.add(new ChatMessage("user", task));
}
```

**defined 模式从空白开始：**

- 只有角色 prompt（system）
- 加上任务描述（user）
- 不继承父历史

**好处：**

- 上下文干净，不受父对话干扰
- 角色更专业（专门的 prompt）
- 省 token（不用带父历史）

**适合：** 审查、测试、探索等独立任务

#### 2. fork 模式 — 继承父历史

```
} else {
    history.addAll(parentHistory);
    history.add(new ChatMessage("user", 
        "【这是一个 fork 出的子 Agent，请独立完成以下任务】\n\n" + task));
}
```

**fork 模式继承父上下文：**

- 复制父 Agent 的所有历史消息
- 追加一个任务说明

**好处：**

- 子 Agent 知道之前的上下文
- 不用重新解释背景
- 首次 LLM 调用命中 prompt cache（快、省 token）

**适合：** 从当前状态分叉出去查问题、做实验

**prompt cache 原理：**

- 父 Agent 已经把历史发给 LLM 了，LLM 缓存了
- fork 的子 Agent 发同样的历史，命中缓存
- 缓存部分不收费、速度快
- 只需要算新增的 task 部分

#### 3. 工具过滤

```
ToolRegistry subTools = parentTools.clone();
if ("defined".equals(mode)) {
    List<String> allowed = def.effectiveTools(
        parentTools.allNames(), globalDisallowed);
    subTools.setWhitelist(allowed);
} else {
    subTools.setBlacklist(globalDisallowed);
}
```

**defined 模式：** 用白名单，只允许角色定义的工具

**fork 模式：** 继承父工具，但黑名单禁用 agent

**★ 全局禁止 agent 工具，防无限嵌套。**

不管什么模式，子 Agent 都不能再派子 Agent。

否则：A 派 B，B 派 C，C 派 D... 无限递归。

#### 4. 模型切换

```
if ("defined".equals(mode) && def.model() != null) {
    provider = parentProvider.withModel(def.model());
}
```

**角色可以指定用什么模型：**

- explore 角色用 haiku（快、便宜，只读任务不需要太强）
- review 角色用 sonnet（平衡）
- 复杂任务用 opus（强但贵）

**好处：** 简单任务用便宜模型，省钱。

#### 5. 权限模式

```
if ("defined".equals(mode) && def.permissionMode() != null) {
    permission = parentPermission.withMode(def.permissionMode());
}
```

**角色可以指定权限模式：**

- explore：plan 模式（只读，禁止写）
- test：accept_edits（允许写测试文件）
- review：plan 模式（只读审查）

**安全：** 探索角色就算被 prompt 注入，也改不了文件。

#### 6. 最大轮数

```
int maxRounds = "defined".equals(mode) ? def.maxRounds() : 15;
```

**防止子 Agent 死循环：**

- explore 角色 10 轮够了（扫一下代码库）
- review 角色 15 轮
- test 角色 20 轮（写测试 + 跑测试 + 修）
- fork 模式默认 15 轮

------

## 第六步：集成到主 Agent

### 注册 agent 工具

```
// 在启动初始化代码中

// 加载角色定义
Map<String, AgentDef> agentDefs = AgentDefLoader.loadAll(
    builtinAgentsDir,
    userAgentsDir,     // ~/.rook/agents/
    projectAgentsDir   // ./.rook/agents/
);

// 创建 agent 工具
AgentTool agentTool = new AgentTool(
    agentDefs,
    new SubAgentRunner(),
    history,           // 父历史（引用，每次执行时取当前状态）
    provider,
    toolRegistry,
    permissionEngine
);

// 注册到工具注册中心
toolRegistry.register(agentTool);
```

### 注意：parentHistory 的引用问题

```
// AgentTool 里存的是 history 的引用
private final List<ChatMessage> parentHistory;

// 但 history 是不断变化的，执行时要取最新的
```

**实现要点：**

- 不要在创建 AgentTool 时复制 history
- 存一个 Supplier 或引用，执行时取当前最新的历史
- 或者每次 execute 时从外部传入当前 history

```
// 更好的方式：用 Supplier
private final Supplier<List<ChatMessage>> historySupplier;

@Override
public ToolResult execute(Map<String, Object> params) {
    List<ChatMessage> currentHistory = historySupplier.get();
    // ...
}
```

------

## 完整流程

### defined 模式流程

```
主 Agent 决定派 explore 子 Agent
    │
    ▼
调用 agent 工具：
  subagent_type: "explore"
  prompt: "帮我看看项目有哪些包"
    │
    ▼
AgentTool.execute()
    ├─ mode = "defined"
    ├─ 找到 explore 角色定义
    └─ 调用 SubAgentRunner.run()
    │
    ▼
SubAgentRunner.run()
    ├─ 1. 准备历史：
    │   system: "你是代码探索专家..."
    │   user: "帮我看看项目有哪些包"
    ├─ 2. 工具过滤：
    │   白名单: [read_file, glob, grep]
    │   禁用: [agent, write_file, edit_file, exec_command]
    ├─ 3. 模型: haiku
    ├─ 4. 权限: plan 模式
    ├─ 5. maxRounds: 10
    └─ 6. 启动 AgentLoop
    │
    ▼
子 Agent 独立运行：
  LLM → glob 查看目录 → LLM → read_file 读文件 → ...
  （最多 10 轮）
    │
    ▼
子 Agent 完成，返回结果
    │
    ▼
主 Agent 收到 tool_result：
  "【子 Agent 执行结果】
   模式: defined
   角色: explore
   轮数: 3
   工具调用: 5 次
   --- 输出 ---
   项目结构概览：..."
    │
    ▼
主 Agent 整理结果，回复用户
```

### fork 模式流程

```
主 Agent（已经聊了很多，上下文很长）
    │
    ▼
调用 agent 工具：
  subagent_type: null（不指定）
  prompt: "去查一下这个 bug 的原因"
    │
    ▼
AgentTool.execute()
    ├─ mode = "fork"
    └─ 调用 SubAgentRunner.run()
    │
    ▼
SubAgentRunner.run()
    ├─ 1. 准备历史：
    │   复制父 Agent 的全部历史
    │   + user: "【fork 子 Agent】去查一下这个 bug"
    ├─ 2. 工具：继承父工具，禁用 agent
    ├─ 3. 模型：和父 Agent 一样
    └─ 4. 启动 AgentLoop
    │
    ▼
首次 LLM 调用：
  发送的历史和父 Agent 之前发的一样
  → 命中 prompt cache
  → 快、省 token
    │
    ▼
子 Agent 独立运行（有完整上下文）
    │
    ▼
返回结果给主 Agent
```

------

## 运行验证

### 测试 1：派 explore Agent

```
> 派一个 explore Agent 帮我看看项目有哪些包

[调用工具: agent(subagent_type="explore", prompt="帮我看看项目有哪些包")]

【子 Agent 启动】
模式: defined
角色: explore
模型: haiku
工具: [read_file, glob, grep]
权限: plan

[子 Agent 运行中...]
  glob: src/main/java/**/*.java
  read_file: src/main/java/com/rook/Main.java
  grep: package com.rook

【子 Agent 完成】
轮数: 3
工具调用: 5 次

--- 输出 ---
项目结构概览：
- com.rook.cli: 命令行界面（Main, Repl）
- com.rook.config: 配置加载（AppConfig, ConfigLoader）
- com.rook.core.agent: Agent 核心（AgentLoop, AgentEvent）
- com.rook.llm: LLM 适配（AnthropicProvider, OpenAiProvider）
- com.rook.tools: 工具系统（Tool, ToolRegistry, 各种工具实现）
...
```

### 测试 2：派 review Agent

```
> 派 review Agent 审查一下我刚才的改动

[调用工具: agent(subagent_type="review", prompt="审查当前 git diff 的改动")]

【子 Agent 启动】
角色: review（只读，不能改文件）

[子 Agent 运行中...]
  exec_command: git diff HEAD
  read_file: 相关文件
  grep: 搜索关键代码

【子 Agent 完成】
--- 输出 ---
代码审查报告：

1. 🟡 中等：空指针风险
   位置: UserService.java:45
   问题: getUserById 返回值可能为 null
   建议: 添加 null 检查

2. 🟢 轻微：命名不规范
   ...

总体评价: 8/10，建议修复 NPE 后合入
```

### 测试 3：fork 模式

```
> （聊了很久，上下文很长）
> fork 一个 Agent 去查一下这个错误的原因

[调用工具: agent(prompt="查一下 NullPointerException 的原因")]

【子 Agent 启动】
模式: fork（继承父上下文）
首次 LLM 调用命中 prompt cache ✓

【子 Agent 完成】
--- 输出 ---
根据之前的对话和代码分析，NPE 的原因是...
```

### 测试 4：子 Agent 不能再派子 Agent

```
> （子 Agent 内部）
> 我再派一个 Agent 帮我...

[工具调用被拒绝: agent 工具在子 Agent 中被禁用]
```

------

## 常见坑（详细版）

### 1. 子 Agent 无限嵌套

**现象：** 子 Agent 又派子 Agent，子子 Agent 又派... 无限递归。

**原因：** 没有禁止子 Agent 使用 agent 工具。

**解决：**

```
private static final List<String> GLOBAL_DISALLOWED = List.of("agent");
```

- 全局禁止，不管角色定义怎么写
- 子 Agent 的工具列表里没有 agent
- AI 想调用也调用不了

### 2. fork 模式的历史引用问题

**现象：** fork 的子 Agent 拿到的历史是旧的，不是最新的。

**原因：** 创建 AgentTool 时复制了 history，之后 history 变了但子 Agent 看不到。

**解决：**

- 用 Supplier 或每次执行时传入当前 history
- 不要在构造时复制，要在 execute 时取最新

### 3. 子 Agent 改了父 Agent 的文件

**现象：** 子 Agent 写文件，影响了主 Agent 的工作区。

**原因：** 子 Agent 和父 Agent 共享同一个工作目录。

**解决：**

- 这一章先共享目录，下一章（Worktree 隔离）解决
- 或者用权限模式限制（plan 模式不能写）
- defined 角色一般都限制工具和权限

### 4. 子 Agent 上下文太长

**现象：** fork 模式继承了很长的父历史，子 Agent 也超 token。

**原因：** 父历史已经很长了，fork 过去更长。

**解决：**

- fork 前先做上下文压缩（第 15 章）
- 或者 defined 模式（空白上下文，省 token）
- 子 Agent 自己也有上下文压缩

### 5. 角色不存在

**现象：** AI 调用了一个不存在的 subagent_type。

**原因：** AI 幻觉了一个角色名，或者角色没加载到。

**解决：**

- description 里列出所有可用角色
- 不存在时返回明确的错误："未知角色: xxx，可用角色: explore, review, test"
- AI 看到错误会调整

### 6. 子 Agent 死循环

**现象：** 子 Agent 一直调用工具，停不下来。

**原因：** 没有最大轮数限制，或者限制太大。

**解决：**

- 每个角色定义 maxRounds
- AgentLoop 里强制限制
- 连续错误也会提前退出（consecutiveErrorLimit）

### 7. 模型切换失败

**现象：** 角色指定了 haiku 模型，但 Provider 不支持切换。

**原因：** Provider 没有实现 withModel 方法。

**解决：**

- LlmProvider 接口加 withModel 方法
- 或者子 Agent 创建新的 Provider 实例
- 不支持的话就用默认模型，记个警告

### 8. 子 Agent 结果太长

**现象：** 子 Agent 返回的结果很长，占很多 token。

**原因：** 子 Agent 的完整输出都返回给父 Agent。

**解决：**

- 子 Agent 结果可以截断（只返回关键部分）
- 或者子 Agent 自己总结后再返回
- 大结果存盘，返回文件路径（第 15 章的 ToolResultStorage）

### 9. 并发安全

**现象：** 多个子 Agent 同时写同一个文件，冲突。

**原因：** 子 Agent 并行执行，共享文件系统。

**解决：**

- 当前是同步执行（一个一个来），没问题
- 后续做并行时，需要 Worktree 隔离（下一章）
- 或者文件锁

------

## 架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                          主 Agent                                │
│                                                                 │
│  history: [系统提示, 用户消息, ...]                              │
│  tools: [read_file, write_file, ..., agent]                     │
│                                                                 │
│  调用 agent 工具:                                                │
│    subagent_type: "explore" 或 null                             │
│    prompt: "任务描述"                                            │
│                                                                 │
└──────────────────────────────┬──────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                          AgentTool                               │
│                                                                 │
│  execute(params):                                               │
│    mode = subagent_type != null ? "defined" : "fork"            │
│    runner.run(mode, ...)                                        │
│                                                                 │
│  ★ 全局禁止: GLOBAL_DISALLOWED = ["agent"]                      │
│                                                                 │
└──────────────────────────────┬──────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                       SubAgentRunner                             │
│                                                                 │
│  defined 模式:                    fork 模式:                     │
│  ┌─────────────────────┐         ┌─────────────────────┐        │
│  │ history:            │         │ history:            │        │
│  │   system: 角色prompt │         │   复制父历史         │        │
│  │   user: 任务         │         │   + user: 任务       │        │
│  │                     │         │                     │        │
│  │ tools: 白名单        │         │ tools: 继承父工具    │        │
│  │ model: 角色指定      │         │        - agent      │        │
│  │ permission: 角色指定 │         │                     │        │
│  │ maxRounds: 角色指定  │         │ model: 同父          │        │
│  └─────────────────────┘         │ maxRounds: 15       │        │
│                                  └─────────────────────┘        │
│                                                                 │
│  → 启动独立的 AgentLoop → 运行 → 返回 SubAgentResult            │
│                                                                 │
└──────────────────────────────┬──────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                        角色定义文件                               │
│                                                                 │
│  .rook/agents/explore/agent.md                                  │
│  ---                                                            │
│  name: explore                                                  │
│  tools: [read_file, glob, grep]                                 │
│  disallowedTools: [agent, write_file, ...]                      │
│  maxRounds: 10                                                  │
│  model: haiku                                                   │
│  permissionMode: plan                                           │
│  ---                                                            │
│  你是代码探索专家...                                             │
│                                                                 │
│  三级加载: 内置 < 用户 < 项目                                    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

------

## defined vs fork 对比

|     维度     |          defined 模式          |       fork 模式        |
| :----------: | :----------------------------: | :--------------------: |
|   触发条件   |       指定 subagent_type       |      不指定 type       |
|    上下文    |    空白（只有角色 prompt）     |       继承父历史       |
|     工具     |           角色白名单           | 继承父工具（禁 agent） |
|     模型     |       可指定（如 haiku）       |       同父 Agent       |
|     权限     |       可指定（如 plan）        |       同父 Agent       |
|   最大轮数   |            角色定义            |        默认 15         |
| Prompt Cache |       不命中（新上下文）       |    命中（继承历史）    |
|   适用场景   | 专业角色（审查 / 测试 / 探索） |     从当前状态分叉     |
|     优点     |      专业、干净、省 token      | 有上下文、不用重述背景 |
|     缺点     |  没有上下文，可能需要重新了解  |     上下文可能很长     |

------

## 下一章

[24-Worktree 隔离.md](./24-Worktree隔离.md) — Git Worktree 让子 Agent 在独立目录干活，互不干扰
