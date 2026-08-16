# 20 — Skill 系统：可复用 AI 操作

## 这一章做什么

把可复用的 AI 操作封装成 **SKILL.md**（YAML frontmatter + Markdown 正文），启动时自动注册为斜杠命令。内置 commit /review/test 三个样板。

**完成后你能做到：**

- 代码审查？输入 `/review`，自动执行完整 SOP
- 跑测试？输入 `/test`，自动运行 + 分析失败原因
- 提交代码？输入 `/commit`，自动生成规范的 commit message
- 想加新操作？写一个 SKILL.md 放进目录，自动生效

## 为什么需要

### 没有 Skill 的问题

每次让 AI 做复杂操作，都要重复输入长长的要求：

```
用户：请对我刚才的代码修改进行全面的代码审查，包括：
1. 用 git diff 查看变更
2. 逐文件分析正确性、安全、风格、性能
3. 给出严重度评级和修复建议
4. 最后给总体评价

（下次还要再输一遍...）
```

**问题：**

- 重复输入，很烦
- 每次写的要求可能不一样，结果不稳定
- 团队成员之间的 SOP 不统一

### 有 Skill 之后

```
用户：/review

（自动加载 review Skill 的完整 SOP，AI 按步骤执行）
```

**Skill 把 SOP 固化成文件，一次编写，反复使用。**

## 核心概念

### SKILL.md 格式

- `---` 包裹的 YAML frontmatter（元数据）
- Markdown 正文（SOP 指令）
- 文件名就是 skill 名

### 三级加载

- 内置（builtin）→ 用户（user）→ 项目（project）
- 优先级从低到高，后者覆盖前者
- 项目级可以覆盖内置和用户级

### 两阶段加载

- 启动时：只加载 name + description（轻量，快）
- 激活时：才加载完整正文（按需，省内存）

### 工具白名单

- `allowed_tools` 字段窄化当前可用工具
- 提高 AI 选对工具的概率
- 防止 Skill 用不该用的工具

### $ARGUMENTS 占位符

- 正文中的 `$ARGUMENTS` 运行时替换为用户参数
- 比如 `/review src/main/` → 替换成 `src/main/`

------

## 第一步：SKILL.md 格式详解

```
---
name: review
description: "审查当前代码变更，给出严重度评级和修复建议"
allowed_tools: [read_file, exec_command, grep, glob]
mode: inline
hidden: false
---

你是一个资深代码审查专家。请按以下步骤对当前代码变更进行审查：

## 步骤 1：查看变更
用 `exec_command` 执行 `git diff HEAD`，了解所有修改。

## 步骤 2：逐文件分析
对每个修改的文件，分析以下方面：
- **正确性**：逻辑是否正确，有没有潜在 bug
- **安全性**：有没有安全隐患（注入、越权等）
- **代码风格**：是否符合项目规范
- **性能**：有没有明显的性能问题

## 步骤 3：给出建议
对每个问题给出：
- 严重度（🔴 严重 / 🟡 中等 / 🟢 轻微）
- 具体位置（文件:行号）
- 修复建议

## 步骤 4：总体评价
最后给出总体评价和是否建议合入。

$ARGUMENTS
```

### frontmatter 字段详解

|      字段       | 必填 |              含义              |        示例         |
| :-------------: | :--: | :----------------------------: | :-----------------: |
|     `name`      |  ✅   | Skill 唯一标识，也是斜杠命令名 |      `review`       |
|  `description`  |  ✅   |  简短描述，用于 /help 和补全   | "审查当前代码变更"  |
| `allowed_tools` |  ❌   |   工具白名单，不填则全部可用   | `[read_file, grep]` |
|     `mode`      |  ❌   |  执行模式：inline /standalone  |      `inline`       |
|    `hidden`     |  ❌   |  是否隐藏（不在 /help 显示）   |       `false`       |

### 命名规范

```
name 必须匹配: [a-z][a-z0-9-]*
```

- 必须小写字母开头
- 只能包含小写字母、数字、连字符
- 因为同时用作斜杠命令名（`/review`）

**合法：**`review`、`test-unit`、`commit-fixup`

**非法：**`Review`、`123`、`test_unit`（下划线不行）

### mode 字段

|     mode     |               含义               |         场景         |
| :----------: | :------------------------------: | :------------------: |
|   `inline`   |  注入到当前对话，继续当前上下文  |     大多数 Skill     |
| `standalone` | 开一个独立的子会话，不影响主对话 | 复杂的、长时间的操作 |

我们先实现 inline 模式，standalone 后续扩展。

------

## 第二步：SkillDef — 数据结构

```
// skills/SkillDef.java
package com.rook.skills;

import java.util.List;

public record SkillDef(
    String name,              // Skill 名
    String description,       // 描述
    List<String> allowedTools,// 工具白名单
    String mode,              // 执行模式
    boolean hidden,           // 是否隐藏
    String body,              // 完整正文（Markdown）
    java.nio.file.Path sourceFile  // 源文件路径
) {
    /**
     * 轻量版本：只含元数据，不含正文
     * 用于启动时快速加载
     */
    public SkillDef lightCopy() {
        return new SkillDef(
            name, description, allowedTools, mode, hidden, 
            null,  // body 为 null，表示未加载
            sourceFile
        );
    }

    /**
     * 是否已加载完整正文
     */
    public boolean isBodyLoaded() {
        return body != null;
    }
}
```

### 为什么需要 lightCopy？

**两阶段加载：**

- 启动时：只加载元数据（name、description 等），body 为 null
- 激活时：从文件读取完整 body

**好处：**

- 启动快：不用读所有 Skill 的完整正文
- 省内存：没用到的 Skill 不占内存
- Skill 多了也不怕

------

## 第三步：SkillLoader — 三级加载

```
// skills/SkillLoader.java
package com.rook.skills;

import org.yaml.snakeyaml.Yaml;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class SkillLoader {

    private static final Yaml YAML = new Yaml();

    /**
     * 三级加载：内置 → 用户 → 项目
     * 优先级从低到高，后者覆盖前者
     */
    public static Map<String, SkillDef> loadAll(
            Path builtinDir,
            Path userDir,
            Path projectDir) throws IOException {
        
        Map<String, SkillDef> result = new LinkedHashMap<>();

        // 第 1 级：内置（最低优先级）
        if (builtinDir != null && Files.exists(builtinDir)) {
            scan(builtinDir).forEach(s -> result.put(s.name(), s));
        }

        // 第 2 级：用户（覆盖内置）
        if (userDir != null && Files.exists(userDir)) {
            scan(userDir).forEach(s -> result.put(s.name(), s));
        }

        // 第 3 级：项目（最高优先级，覆盖用户+内置）
        if (projectDir != null && Files.exists(projectDir)) {
            scan(projectDir).forEach(s -> result.put(s.name(), s));
        }

        return result;
    }

    /**
     * 扫描一个目录下的所有 SKILL.md
     * 支持子目录：每个子目录是一个 Skill，里面有 SKILL.md
     */
    public static List<SkillDef> scan(Path dir) throws IOException {
        List<SkillDef> skills = new ArrayList<>();

        if (!Files.exists(dir)) {
            return skills;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    // 子目录里找 SKILL.md
                    Path skillFile = entry.resolve("SKILL.md");
                    if (Files.exists(skillFile)) {
                        try {
                            SkillDef skill = parse(skillFile);
                            skills.add(skill);
                        } catch (Exception e) {
                            System.err.println("[Skill] 加载失败: " 
                                + skillFile + " - " + e.getMessage());
                        }
                    }
                } else if (entry.getFileName().toString().equals("SKILL.md")) {
                    // 直接在目录下的 SKILL.md（目录名就是 skill 名）
                    try {
                        SkillDef skill = parse(entry);
                        skills.add(skill);
                    } catch (Exception e) {
                        System.err.println("[Skill] 加载失败: " 
                            + entry + " - " + e.getMessage());
                    }
                }
            }
        }

        return skills;
    }

    /**
     * 解析单个 SKILL.md 文件
     */
    public static SkillDef parse(Path file) throws IOException {
        String content = Files.readString(file);

        // 分割 frontmatter 和正文
        int firstSep = content.indexOf("---");
        if (firstSep != 0) {
            throw new IllegalArgumentException("缺少 frontmatter");
        }
        int secondSep = content.indexOf("---", 3);
        if (secondSep < 0) {
            throw new IllegalArgumentException("frontmatter 未闭合");
        }

        String yamlPart = content.substring(3, secondSep).trim();
        String body = content.substring(secondSep + 3).trim();

        // 解析 YAML
        Map<String, Object> fm = YAML.load(yamlPart);

        String name = (String) fm.get("name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("缺少 name 字段");
        }

        // 校验命名规范
        if (!name.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException(
                "name 不合法: " + name + "，必须匹配 [a-z][a-z0-9-]*");
        }

        String description = (String) fm.getOrDefault("description", "");
        String mode = (String) fm.getOrDefault("mode", "inline");
        boolean hidden = (Boolean) fm.getOrDefault("hidden", false);

        @SuppressWarnings("unchecked")
        List<String> allowedTools = (List<String>) fm.getOrDefault(
            "allowed_tools", List.of());

        return new SkillDef(
            name, description, allowedTools, mode, hidden, body, file
        );
    }

    /**
     * 只加载元数据（轻量版）
     */
    public static SkillDef parseLight(Path file) throws IOException {
        return parse(file).lightCopy();
    }

    /**
     * 加载完整正文（激活时调用）
     */
    public static String loadBody(Path file) throws IOException {
        String content = Files.readString(file);
        int secondSep = content.indexOf("---", 3);
        return content.substring(secondSep + 3).trim();
    }
}
```

### 逐段详解

#### 1. 三级覆盖逻辑

```
// 内置
scan(builtinDir).forEach(s -> result.put(s.name(), s));
// 用户（覆盖内置）
scan(userDir).forEach(s -> result.put(s.name(), s));
// 项目（覆盖用户+内置）
scan(projectDir).forEach(s -> result.put(s.name(), s));
```

**LinkedHashMap + 同名覆盖，简单有效。**

**三级目录：**

| 级别 |                       路径                        |       用途       | 优先级 |
| :--: | :-----------------------------------------------: | :--------------: | :----: |
| 内置 | `classpath:/skills/` 或 `~/.rook/skills-builtin/` | 随程序发布的样板 |  最低  |
| 用户 |                 `~/.rook/skills/`                 | 用户个人的 Skill |   中   |
| 项目 |                 `./.rook/skills/`                 | 项目共享的 Skill |  最高  |

**为什么项目级最高？**

- 项目有自己的规范，应该覆盖全局默认
- 比如团队约定了 commit message 格式，项目级的 commit Skill 覆盖内置的

#### 2. 目录结构

**支持两种布局：**

**布局 A：每个 Skill 一个子目录（推荐）**

```
skills/
├── review/
│   └── SKILL.md
├── test/
│   └── SKILL.md
└── commit/
    └── SKILL.md
```

**布局 B：直接放 SKILL.md（目录名就是 skill 名）**

```
skills/
└── SKILL.md  ← 不推荐，只有一个 Skill 时用
```

推荐布局 A，清晰，而且 Skill 可以带附属文件（模板、脚本等）。

#### 3. 解析 frontmatter

```
int firstSep = content.indexOf("---");
int secondSep = content.indexOf("---", 3);
String yamlPart = content.substring(3, secondSep).trim();
String body = content.substring(secondSep + 3).trim();
```

**手动解析，不依赖复杂的 Markdown 解析器。**

- 第一个 `---` 必须在开头（index 0）
- 第二个 `---` 结束 frontmatter
- 中间是 YAML，后面是正文

#### 4. 命名校验

```
if (!name.matches("[a-z][a-z0-9-]*")) {
    throw new IllegalArgumentException("name 不合法: " + name);
}
```

**启动时校验，不合法直接报错。**

因为 name 同时用作斜杠命令名，必须符合命令命名规范。

#### 5. 单个失败不影响其他

```
try {
    SkillDef skill = parse(skillFile);
    skills.add(skill);
} catch (Exception e) {
    System.err.println("[Skill] 加载失败: " + skillFile + " - " + e.getMessage());
}
```

某个 Skill 写坏了，不影响其他 Skill 加载。

打印警告，继续。

------

## 第四步：SkillRegistry — 注册中心

```
// skills/SkillRegistry.java
package com.rook.skills;

import java.io.IOException;
import java.util.*;

public class SkillRegistry {

    // 存储：name → SkillDef（可能是轻量版）
    private final Map<String, SkillDef> skills = new LinkedHashMap<>();

    /**
     * 注册一个 Skill（通常是轻量版）
     */
    public void register(SkillDef skill) {
        skills.put(skill.name(), skill);
    }

    /**
     * 批量注册
     */
    public void registerAll(Map<String, SkillDef> skillMap) {
        skills.putAll(skillMap);
    }

    /**
     * 查找 Skill（可能是轻量版，body 为 null）
     */
    public Optional<SkillDef> find(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    /**
     * 激活 Skill：加载完整正文
     * 如果已经是完整版，直接返回
     */
    public SkillDef activate(String name) throws IOException {
        SkillDef skill = skills.get(name);
        if (skill == null) {
            throw new IllegalArgumentException("Skill 不存在: " + name);
        }

        if (skill.isBodyLoaded()) {
            return skill;  // 已经加载过了
        }

        // 从源文件加载完整正文
        String body = SkillLoader.loadBody(skill.sourceFile());
        SkillDef full = new SkillDef(
            skill.name(), skill.description(), skill.allowedTools(),
            skill.mode(), skill.hidden(), body, skill.sourceFile()
        );

        skills.put(name, full);  // 缓存完整版
        return full;
    }

    /**
     * 获取所有可见 Skill（用于 /help 和补全）
     */
    public List<SkillDef> listVisible() {
        return skills.values().stream()
            .filter(s -> !s.hidden())
            .toList();
    }

    /**
     * 获取所有 Skill
     */
    public List<SkillDef> all() {
        return new ArrayList<>(skills.values());
    }

    /**
     * Tab 补全
     */
    public List<String> complete(String prefix) {
        return skills.keySet().stream()
            .filter(name -> name.startsWith(prefix))
            .toList();
    }

    public int size() {
        return skills.size();
    }
}
```

### 逐段详解

#### 1. 激活时加载正文

```
public SkillDef activate(String name) throws IOException {
    SkillDef skill = skills.get(name);
    if (skill.isBodyLoaded()) {
        return skill;  // 已经加载过了
    }
    String body = SkillLoader.loadBody(skill.sourceFile());
    SkillDef full = new SkillDef(..., body, ...);
    skills.put(name, full);  // 缓存
    return full;
}
```

**两阶段加载的核心：**

- 启动时：只有元数据，body = null
- 激活时：读文件，加载 body，缓存起来
- 再次激活：直接返回缓存的完整版

**第一次用慢一点（读文件），之后都快。**

#### 2. 补全和列表

```
public List<SkillDef> listVisible() {
    return skills.values().stream()
        .filter(s -> !s.hidden())
        .toList();
}
```

和 CommandRegistry 类似，隐藏的 Skill 不在 /help 显示。

------

## 第五步：自动注册斜杠命令

每个 Skill 启动时自动注册为 `/skillname` 命令。

```
// 在启动初始化代码中

public void initSkills(
        SkillRegistry skillRegistry,
        CommandRegistry cmdRegistry,
        ToolRegistry toolRegistry) {

    for (SkillDef s : skillRegistry.all()) {
        // 检查是否和内置命令冲突
        if (cmdRegistry.find("/" + s.name()).isPresent()) {
            System.err.println("[Skill] 与内置命令冲突，跳过: " + s.name());
            continue;
        }

        // 注册为 PROMPT_FEED 类型的命令
        Command cmd = new Command(
            "/" + s.name(),
            List.of(),
            "[Skill] " + s.description(),
            "/" + s.name() + " [参数]",
            CommandType.PROMPT_FEED,
            s.hidden(),
            (console, args) -> {
                try {
                    // 激活 Skill（加载完整正文）
                    SkillDef full = skillRegistry.activate(s.name());

                    // 应用工具白名单
                    if (!full.allowedTools().isEmpty()) {
                        toolRegistry.setWhitelist(full.allowedTools());
                    }

                    // 替换 $ARGUMENTS 占位符
                    String prompt = applyArguments(full.body(), args);

                    console.printSuccess("[Skill] 已激活: " + s.name());
                    return prompt;

                } catch (Exception e) {
                    console.printError("[Skill] 激活失败: " + e.getMessage());
                    return null;
                }
            }
        );

        cmdRegistry.register(cmd);
    }
}

/**
 * 替换 $ARGUMENTS 占位符
 * 如果正文不含占位符且参数非空，自动追加到末尾
 */
private String applyArguments(String body, String args) {
    if (args == null || args.isBlank()) {
        // 没有参数，移除占位符（避免显示 $ARGUMENTS）
        return body.replace("$ARGUMENTS", "");
    }

    if (body.contains("$ARGUMENTS")) {
        return body.replace("$ARGUMENTS", args);
    } else {
        // 正文没有占位符，自动追加到末尾
        return body + "\n\n## 额外参数\n" + args;
    }
}
```

### 逐段详解

#### 1. 冲突检测

```
if (cmdRegistry.find("/" + s.name()).isPresent()) {
    System.err.println("[Skill] 与内置命令冲突，跳过: " + s.name());
    continue;
}
```

**内置命令优先，Skill 冲突时静默跳过。**

比如有个 Skill 叫 `help`，但 `/help` 已经是内置命令了，这个 Skill 就不会注册。

打印警告，让用户知道。

#### 2. PROMPT_FEED 类型

```
CommandType.PROMPT_FEED
```

Skill 命令都是 PROMPT_FEED 类型：

- 执行后返回提示词
- 提示词注入到对话
- 触发 LLM 按 SOP 执行

#### 3. 激活 + 工具白名单

```
SkillDef full = skillRegistry.activate(s.name());

if (!full.allowedTools().isEmpty()) {
    toolRegistry.setWhitelist(full.allowedTools());
}
```

**激活时做两件事：**

1. 加载完整正文
2. 设置工具白名单（如果有）

**工具白名单的作用：**

- 窄化 AI 可选的工具
- 提高选对工具的概率
- 比如 review Skill 只允许 read_file/exec_command/grep/glob
- 防止 AI 乱调用 write_file 改代码（审查不该改代码）

#### 4. $ARGUMENTS 替换

```
private String applyArguments(String body, String args) {
    if (args == null || args.isBlank()) {
        return body.replace("$ARGUMENTS", "");
    }
    if (body.contains("$ARGUMENTS")) {
        return body.replace("$ARGUMENTS", args);
    } else {
        return body + "\n\n## 额外参数\n" + args;
    }
}
```

**三种情况：**

|       情况       |              处理               |
| :--------------: | :-----------------------------: |
| 有占位符，有参数 |           替换占位符            |
| 有占位符，无参数 | 移除占位符（不显示 $ARGUMENTS） |
| 无占位符，有参数 |         自动追加到末尾          |

**示例：**

```
正文：...审查以下文件：$ARGUMENTS
用户输入：/review src/main/java/

替换后：...审查以下文件：src/main/java/
```

------

## 第六步：三个内置样板

### 1. review — 代码审查

```
---
name: review
description: "审查当前代码变更，给出严重度评级和修复建议"
allowed_tools: [read_file, exec_command, grep, glob]
mode: inline
---

你是一个资深代码审查专家。请按以下步骤对当前代码变更进行全面审查。

## 步骤 1：查看变更
执行 `git diff HEAD` 了解所有修改。如果变更太多，先看 `git diff --stat` 概览。

## 步骤 2：逐文件分析
对每个修改的文件，深入分析：
- **正确性**：逻辑是否正确，边界条件是否处理，有没有潜在 bug
- **安全性**：有没有注入、越权、敏感信息泄露等安全隐患
- **代码风格**：命名、注释、格式是否符合项目规范
- **性能**：有没有明显的性能问题（N+1、大循环、重复计算等）
- **可维护性**：代码是否清晰易懂，复杂度是否过高

## 步骤 3：给出问题清单
对每个发现的问题，按以下格式输出：
```

### [严重度] 问题标题

- **位置**：文件路径：行号
- **问题**：具体描述
- **建议**：如何修复

```
严重度用：🔴 严重 / 🟡 中等 / 🟢 轻微

## 步骤 4：总体评价
最后给出：
- 总体评分（1-10 分）
- 是否建议合入
- 主要亮点
- 主要风险

$ARGUMENTS
```

### 2. test — 运行测试并分析

```
---
name: test
description: "运行项目测试，分析失败原因并给出修复建议"
allowed_tools: [exec_command, read_file, grep]
mode: inline
---

你是一个测试工程师。请运行项目测试并分析结果。

## 步骤 1：运行测试
根据项目类型选择测试命令：
- Maven: `mvn test`
- Gradle: `./gradlew test`
- npm: `npm test`
- pytest: `pytest`

如果不确定，先查看项目结构（pom.xml / build.gradle / package.json 等）。

## 步骤 2：分析结果
- 如果全部通过：报告通过数量和耗时
- 如果有失败：逐个分析失败原因

## 步骤 3：失败分析
对每个失败的测试：
1. 读取测试代码和相关源码
2. 分析失败原因（断言错误、异常、超时等）
3. 给出修复建议

## 步骤 4：总结
- 通过/失败数量
- 失败原因分类
- 修复优先级建议

$ARGUMENTS
```

### 3. commit — 生成 commit message

```
---
name: commit
description: "根据 git diff 生成规范的 commit message"
allowed_tools: [exec_command, read_file]
mode: inline
---

你是一个 Git 提交专家。请根据当前变更生成规范的 commit message。

## 步骤 1：查看变更
执行 `git diff --cached`（暂存区），如果为空则看 `git diff`（工作区）。
同时执行 `git status` 了解修改的文件。

## 步骤 2：分析变更类型
判断变更类型：
- feat: 新功能
- fix: 修复 bug
- docs: 文档
- style: 格式（不影响代码运行）
- refactor: 重构
- perf: 性能优化
- test: 测试
- chore: 构建/工具

## 步骤 3：生成 commit message
遵循 Conventional Commits 规范：
```

<type>(<scope>): <subject>

<body><footer> ``` 要求： - subject 不超过 50 字符，用祈使句 - body 详细说明变更内容和原因 - 有关联 issue 则在 footer 引用

## 步骤 4：确认

输出生成的 commit message，并询问用户是否确认提交。

```
---

## 完整流程

### Skill 加载流程
程序启动

│

▼

SkillLoader.loadAll (builtin, user, project)

│

├─ 扫描内置目录 → 解析元数据（轻量）

├─ 扫描用户目录 → 覆盖内置

└─ 扫描项目目录 → 覆盖用户 + 内置

│

▼

SkillRegistry.registerAll (skills)

│

▼

自动注册斜杠命令

├─ 遍历所有 Skill

├─ 检查与内置命令冲突

└─ 注册为 /skillname 命令（PROMPT_FEED）

│

▼

启动完成，Skill 命令可用
```

```
### Skill 执行流程
用户输入: /review src/main/

│

▼

Repl 检测到 / 开头 → 命令分发

│

▼

找到 /review 命令（Skill 自动注册的）

│

▼

执行 handler

├─ skillRegistry.activate ("review")

│   └─ 从文件加载完整正文，缓存

├─ 应用工具白名单（read_file, exec_command, grep, glob）

└─ 替换 $ARGUMENTS → "src/main/"

│

▼

返回完整的 SOP 提示词

│

▼

PROMPT_FEED → 注入到对话

│

▼

LLM 收到 SOP，按步骤执行

├─ git diff 查看变更

├─ 逐文件分析

├─ 给出问题清单

└─ 总体评价
```

```
---

## 运行验证

### 测试 1：查看 Skill 列表

```cmd
> /help
可用命令：

  /help            显示所有命令的帮助信息
  /exit            退出程序
  ...
  /review          [Skill] 审查当前代码变更，给出严重度评级和修复建议
  /test            [Skill] 运行项目测试，分析失败原因并给出修复建议
  /commit          [Skill] 根据 git diff 生成规范的 commit message
```

Skill 命令自动出现在 /help 里，描述前有 `[Skill]` 标记。

### 测试 2：执行 review Skill

```
> /review

[Skill] 已激活: review

（AI 开始执行）
正在查看变更...
git diff HEAD 显示修改了 3 个文件：
- src/main/java/.../UserService.java
- src/main/java/.../UserController.java
- src/test/java/.../UserServiceTest.java

逐文件分析中...

### 🟡 中等：空指针风险
- 位置: UserService.java:45
- 问题: getUserById 返回值可能为 null，直接调用 getName() 会 NPE
- 建议: 添加 null 检查或使用 Optional

### 🟢 轻微：命名不规范
- 位置: UserController.java:23
- 问题: 变量名 usr 建议改为 user
- 建议: 重命名为 user

## 总体评价
- 评分: 8/10
- 建议合入: 是（修复 NPE 后）
- 亮点: 逻辑清晰，测试覆盖完善
- 风险: 存在一个潜在 NPE
```

### 测试 3：带参数执行

```
> /review src/main/java/com/rook/skills/

[Skill] 已激活: review

（$ARGUMENTS 被替换为 "src/main/java/com/rook/skills/"）
（AI 只审查这个目录下的变更）
```

### 测试 4：自定义 Skill

```
（用户在 ~/.rook/skills/deploy/SKILL.md 写了一个部署 Skill）

> 重启 Rook

> /help
  ...
  /deploy          [Skill] 部署到测试环境

> /deploy staging
[Skill] 已激活: deploy
（开始执行部署 SOP）
```

------

## 常见坑（详细版）

### 1. Skill 命名不合法

**现象：** 启动报错 "name 不合法"。

**原因：** name 用了大写、下划线、中文等。

**解决：**

- 必须匹配 `[a-z][a-z0-9-]*`
- 用小写字母开头，只能有小写、数字、连字符
- 因为同时用作斜杠命令名，必须符合命令规范

### 2. $ARGUMENTS 没写但传了参数

**现象：** 用户传了参数，但 Skill 正文里没有 $ARGUMENTS，参数没生效。

**解决：**

- 我们的实现做了兼容：没有占位符就自动追加到末尾
- 但最好还是在正文里明确写 $ARGUMENTS，控制参数出现的位置
- 比如在步骤 1 里写 "审查范围：$ARGUMENTS"

### 3. 与内置命令冲突

**现象：** Skill 写了但 /skillname 不能用。

**原因：** 和内置命令重名了，内置优先，Skill 静默跳过。

**解决：**

- 启动日志里有警告："[Skill] 与内置命令冲突，跳过: xxx"
- 改个不冲突的名字
- 或者用别名（Skill 目前不支持别名，后续可以加）

### 4. 工具白名单忘记恢复

**现象：** 用了 /review 后，其他对话也只能用白名单里的工具。

**原因：** 激活 Skill 时设置了 toolRegistry.setWhitelist ()，但 Skill 结束后没恢复。

**解决：**

- Skill 执行完（AgentLoop 自然结束）后，恢复工具白名单
- 或者白名单只对当前轮生效，下一轮自动清除
- 我们的实现应该在 AgentLoop 结束后调用 toolRegistry.clearWhitelist ()

### 5. frontmatter 解析失败

**现象：** Skill 加载失败，报 "缺少 frontmatter" 或 "frontmatter 未闭合"。

**原因：**

- `---` 前面有空格或其他字符
- 只有开头的 `---`，没有结尾的
- YAML 语法错误（缩进不对、冒号后没空格）

**解决：**

- `---` 必须在第一行开头
- 必须有闭合的 `---`
- YAML 注意缩进用空格，不能用 Tab
- 字符串有特殊字符用引号包起来

### 6. Skill 正文太长，超 token

**现象：** 激活 Skill 后，加上历史消息超 token 窗口了。

**原因：** Skill 的 SOP 正文很长，注入后占很多 token。

**解决：**

- Skill 正文尽量简洁，只写关键步骤
- 复杂的 Skill 可以分阶段，第一步只做概览，需要时再深入
- 或者用第 15 章的上下文压缩，自动处理

### 7. 项目级 Skill 没生效

**现象：** 项目目录下放了 Skill，但没加载到。

**原因：**

- 路径不对：应该是 `./.rook/skills/<skillname>/SKILL.md`
- 目录名和 name 字段不一致（我们用 name 字段，不用目录名，但建议一致）
- 启动时没传 projectDir 参数

**解决：**

- 检查路径是否正确
- 启动日志里看扫描了哪些目录
- 确认 projectDir 参数传对了

### 8. Skill 改了不生效

**现象：** 改了 SKILL.md，但运行还是旧的。

**原因：** 两阶段加载，激活后缓存了完整版，改文件不重新读。

**解决：**

- 重启程序
- 或者加个 `/skill reload <name>` 命令，强制重新加载
- 开发阶段可以每次激活都重读文件（性能换便利）

------

## 架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                        启动初始化                                 │
│                                                                 │
│  SkillLoader.loadAll(builtin, user, project)                    │
│      ├─ 扫描三级目录                                             │
│      ├─ 解析 frontmatter（轻量，不读正文）                       │
│      └─ 同名覆盖（项目 > 用户 > 内置）                           │
│                                                                 │
│  SkillRegistry.registerAll()                                    │
│                                                                 │
│  自动注册斜杠命令                                                │
│      └─ 每个 Skill → /skillname（PROMPT_FEED）                  │
│                                                                 │
└──────────────────────────────┬──────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                        用户执行 Skill                             │
│                                                                 │
│  用户输入: /review src/main/                                     │
│      │                                                          │
│      ▼                                                          │
│  CommandDispatcher → 找到 /review 命令                           │
│      │                                                          │
│      ▼                                                          │
│  执行 handler                                                   │
│      ├─ skillRegistry.activate("review")                        │
│      │   └─ 从源文件加载完整正文 → 缓存                          │
│      ├─ toolRegistry.setWhitelist([...])                        │
│      └─ body.replace("$ARGUMENTS", "src/main/")                 │
│      │                                                          │
│      ▼                                                          │
│  返回 SOP 提示词（PROMPT_FEED）                                  │
│      │                                                          │
│      ▼                                                          │
│  注入对话 → LLM 按 SOP 执行                                      │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                        文件结构                                   │
│                                                                 │
│  ~/.rook/skills/                ← 用户级                         │
│  ├── review/                                                    
│  │   └── SKILL.md                                              
│  ├── test/                                                      
│  │   └── SKILL.md                                              
│  └── commit/                                                    
│      └── SKILL.md                                              
│                                                                 │
│  ./.rook/skills/                ← 项目级（最高优先级）           │
│  └── deploy/                                                    
│      └── SKILL.md                                              
│                                                                 │
│  SKILL.md 格式:                                                 
│  ---                                                            
│  name: review                                                   
│  description: "..."                                             
│  allowed_tools: [...]                                           
│  ---                                                            
│  正文（SOP 指令）                                                
│  $ARGUMENTS                                                      │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

------

## Skill vs 普通命令 vs 记忆

|   维度   |        普通命令        |           Skill            |        自动记忆        |
| :------: | :--------------------: | :------------------------: | :--------------------: |
| 定义方式 |       Java 代码        |       Markdown 文件        |      AI 自动提取       |
| 触发方式 |        斜杠命令        |    斜杠命令（自动注册）    | 自动注入 System Prompt |
|   内容   |        程序逻辑        |     LLM 提示词（SOP）      |  用户偏好 / 项目知识   |
|   执行   |      本地代码执行      |    注入提示词，LLM 执行    |     影响 LLM 行为      |
| 可扩展性 |         改代码         |           加文件           |        AI 自动         |
| 适用场景 | 系统操作（清屏、退出） | 复杂 AI 操作（审查、测试） |       偏好和知识       |

**三者互补：**

- 普通命令：系统级操作，用代码实现
- Skill：可复用的 AI 操作 SOP，用 Markdown 写
- 记忆：用户偏好和项目知识，AI 自动提取

------

## 下一章

[21-Hook 引擎.md](./21-Hook引擎.md) — 生命周期 11 个事件上挂自动化动作，拦截 / 注入 / 通知
