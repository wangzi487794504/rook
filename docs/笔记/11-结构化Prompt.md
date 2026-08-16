# 11 — 结构化 System Prompt

## 这一章做什么

把硬编码的一整段 system prompt 拆成 **10 个可插拔模块**，按优先级拼装。稳定内容走缓存，变化内容走补充消息。

**完成后你能做到：**

- 加一条新规则？新建一个类，register 一下就行，不用改老代码
- 想临时切换模式（比如计划模式）？改个参数，对应模块自动切换内容
- 稳定的内容缓存起来，不用每次都重新生成
- 变化的内容走补充通道，不影响缓存

## 为什么需要

### 硬编码 Prompt 的痛点

之前的 system prompt 可能是这样的：

```java
String systemPrompt = """
    你是 Rook AI 编程助手 v0.9.0。
    
    规则：
    - 不要编造信息
    - 不要跳过验证步骤
    - 遇到错误要处理
    
    工具使用规则：
    - 优先使用工具获取信息
    - 编辑文件前先读一遍
    - ...
    
    输出格式：
    - 代码用代码块
    - ...
    
    当前模式：正常模式
    ...
    """;
```

**问题：**

- 一长串字符串，改起来麻烦
- 加一条规则要找到地方插进去
- 想临时切换模式？要整段替换
- 内容混在一起，分不清哪些稳定、哪些变化
- 没法缓存，每次都重新拼

### 模块化之后

```
IdentityModule          → "你是 Rook AI 编程助手 v0.9.0"
SystemConstraintsModule → "不要编造、不要跳过..."
TaskModeModule          → "当前模式：正常 / 只读"
ToolUsageModule         → "优先用工具、编辑前先读"
ToneStyleModule         → "中文回复、简洁明了"
...
```

**好处：**

- 每个模块只管自己那一块，职责清晰
- 加新规则 = 新建一个模块类，register 一下
- 可以按优先级排序，重要的放前面
- 稳定的模块缓存起来
- 变化的模块单独处理

## 核心概念

### PromptModule

每个模块是一个独立的类，只负责生成 prompt 的一部分。

**三个方法：**

- `name()` — 模块名称，也是标题
- `priority()` — 优先级，数字越小越靠前
- `render(ctx)` — 根据上下文生成内容

### PromptBuilder

装配器，把所有模块按优先级排好序，逐个 render，然后拼接成完整的 system prompt。

### 两通道机制

- **缓存通道（system 消息）**：稳定不变的内容，放在 messages [0]，可以缓存
- **补充通道（user 消息）**：变化的内容，放在一条特殊的 `[Supplement]` user 消息里，每次更新

**为什么分两通道？**

有些 LLM API 支持缓存 system prompt（比如 Anthropic 的 prompt caching），缓存的部分不收费、速度更快。

- 稳定的放缓存通道，省钱又快
- 变化的放补充通道，不影响缓存命中率

------

## 第一步：PromptModule 接口

```java
// prompt/PromptModule.java
package com.rook.prompt;

public interface PromptModule {
    
    /**
     * 模块名称，也会作为 section 标题
     * 比如 "身份"、"工具使用规则"
     */
    String name();
    
    /**
     * 优先级，数字越小越靠前
     * 约定：
     *   1-70  — 固定核心模块（总是存在）
     *   71-100 — 可选模块（按需注册）
     *   100+   — 用户自定义模块
     */
    int priority();
    
    /**
     * 根据上下文渲染本模块的内容
     * @param ctx 上下文，包含当前模式、环境信息等
     * @return 渲染后的文本，如果返回空字符串表示本模块不输出
     */
    String render(PromptContext ctx);
}
```

### 为什么用接口？

**面向接口编程，方便扩展：**

- 内置模块实现这个接口
- 用户自定义模块也实现这个接口
- PromptBuilder 只依赖接口，不依赖具体实现

**加新模块不用改 Builder 代码。**

### 三个方法详解

#### name () — 模块名称

- 简短的标题，比如 "身份"、"工具使用规则"
- 会作为 section 的标题输出
- 也用于调试和日志

#### priority () — 优先级

- 数字越小，越靠前
- 为什么重要的放前面？因为 LLM 对前面的内容记忆更深

**优先级区间约定：**

|  区间  |     用途     |            说明            |
| :----: | :----------: | :------------------------: |
|  1-70  | 固定核心模块 |     总是存在，基本不变     |
| 71-100 |   可选模块   | 按需注册，可能有也可能没有 |
|  100+  |  用户自定义  |      用户自己加的模块      |

**为什么留这么多间隔？**

方便以后在中间插新模块，不用调整所有优先级。

#### render (ctx) — 渲染

- 根据上下文生成内容
- 返回空字符串表示本模块不输出（可选模块用）
- ctx 里有什么？当前模式、环境信息、用户偏好...

------

## 第二步：7 个固定模块

### 模块总览

| 优先级 |          模块           |                 职责                 |             内容示例             |
| :----: | :---------------------: | :----------------------------------: | :------------------------------: |
|   1    |     IdentityModule      |      Agent 名称、版本、角色定位      |  "你是 Rook AI 编程助手 v0.9.0"  |
|   2    | SystemConstraintsModule |  基本约束：不编造、不跳过、错误处理  |      "不要编造不存在的信息"      |
|   3    |     TaskModeModule      |     任务模式：正常 / 只读 / 计划     | "当前处于计划模式，只分析不修改" |
|   4    |  ActionExecutionModule  |    行动执行流程：调工具→回灌→多轮    |    "调用工具后等待结果再继续"    |
|   5    |     ToolUsageModule     | 工具使用约定：优先用工具、编辑前先读 |      "编辑文件前必须先读取"      |
|   6    |     ToneStyleModule     |         语气风格：中文、简洁         |      "用中文回复，简洁明了"      |
|   7    |    TextOutputModule     |         输出格式：代码块规范         |        "代码用``` 包裹 "         |

------

### 模块 1：IdentityModule（优先级 1）

告诉 AI 它是谁。

```java
// prompt/impl/IdentityModule.java
public class IdentityModule implements PromptModule {
    
    private final String version;
    
    public IdentityModule(String version) {
        this.version = version;
    }
   
    @Override
    public String name() { return "身份"; }
    
    @Override
    public int priority() { return 1; }
    
    @Override
    public String render(PromptContext ctx) {
        return """
            你是 Rook —— 一个命令行 AI 编程助手，版本 %s。
            
            你的职责是帮助用户完成编程相关的任务：
            - 阅读和理解代码
            - 修改和编写代码
            - 调试和修复问题
            - 执行命令和查看结果
            
            你可以使用提供的工具来操作文件系统和执行命令。
            """.formatted(version);
    }
}
```

**为什么放最前面？**

身份定位是最基础的，LLM 首先要知道自己是谁、要干嘛。

------

### 模块 2：SystemConstraintsModule（优先级 2）

基本行为约束，红线规则。

```java
// prompt/impl/SystemConstraintsModule.java
public class SystemConstraintsModule implements PromptModule {
    
    @Override
    public String name() { return "基本约束"; }
    
    @Override
    public int priority() { return 2; }
    
    @Override
    public String render(PromptContext ctx) {
        return """
            【重要规则】
            
            1. 不要编造信息
               - 不确定的内容，用工具去查，不要猜
               - 文件内容、命令输出等，必须通过工具获取
               - 不知道就说不知道，不要瞎编
            
            2. 不要跳过验证步骤
               - 改完代码要跑测试验证
               - 重要操作要确认结果
            
            3. 遇到错误要处理
               - 工具调用失败了，分析原因，尝试解决
               - 解决不了，告诉用户错误信息和你的分析
            
            4. 安全第一
               - 危险操作要提醒用户确认
               - 不要执行可能破坏系统的命令
            """;
    }
}
```

**为什么优先级这么高？**

这些是红线规则，必须让 LLM 牢牢记住，所以放前面。

------

### 模块 3：TaskModeModule（优先级 3）

任务模式切换，比如正常模式、只读模式、计划模式。

```java
// prompt/impl/TaskModeModule.java
public class TaskModeModule implements PromptModule {
    
    @Override
    public String name() { return "任务模式"; }
    
    @Override
    public int priority() { return 3; }
    
    @Override
    public String render(PromptContext ctx) {
        return switch (ctx.mode()) {
            case NORMAL -> """
                当前模式：正常模式
                
                你可以自由使用所有工具，包括读写文件、执行命令。
                主动完成任务，不需要用户每一步确认。
                """;
                
            case PLAN_ONLY -> """
                当前模式：计划模式（只读）
                
                重要：你只能分析和给出建议，不能实际修改任何东西！
                - 可以读文件、搜索、查看
                - 不要写文件、不要改文件、不要执行会修改系统的命令
                - 给出详细的计划和步骤，让用户决定是否执行
                """;
                
            case ASK_FIRST -> """
                当前模式：确认模式
                
                每次执行写入或修改操作前，先询问用户是否确认。
                只读操作可以直接执行。
                """;
        };
    }
}
```

**为什么这个模块重要？**

- 不同模式下，AI 的行为完全不同
- 比如计划模式下，AI 只能看不能改
- 这个模块控制 AI 的 "行动权限"

**这就是模块化的好处：**

模式切换不用改整个 prompt，只改这一个模块的输出就行。

------

### 模块 4：ActionExecutionModule（优先级 4）

告诉 AI 怎么执行行动：调工具、等结果、多轮循环。

```java
// prompt/impl/ActionExecutionModule.java
public class ActionExecutionModule implements PromptModule {
    
    @Override
    public String name() { return "行动执行"; }
    
    @Override
    public int priority() { return 4; }
    
    @Override
    public String render(PromptContext ctx) {
        return """
            【行动执行流程】
            
            1. 思考需要做什么
               - 分析用户的需求
               - 决定需要调用哪些工具
            
            2. 调用工具
               - 一次可以调用多个工具
               - 只读工具可以并发调用
               - 调用时参数要准确
            
            3. 等待工具结果
               - 工具执行完会返回结果
               - 仔细阅读结果，不要跳过
            
            4. 根据结果继续
               - 结果正常 → 继续下一步
               - 结果有错误 → 分析原因，调整后重试
               - 不需要更多工具了 → 给出最终回答
            
            5. 多轮迭代
               - 可以多轮调用工具，直到任务完成
               - 不要放弃，遇到错误要尝试解决
            """;
    }
}
```

**为什么需要这个？**

有些 LLM 不知道工具调用的流程，比如：

- 调了工具就直接回答，不等结果
- 一次只调一个工具，不知道可以并发
- 遇到错误就放弃，不知道重试

明确告诉它流程，效果会好很多。

------

### 模块 5：ToolUsageModule（优先级 5）

工具使用的具体约定。

```java
// prompt/impl/ToolUsageModule.java
public class ToolUsageModule implements PromptModule {
    
    @Override
    public String name() { return "工具使用规则"; }
    
    @Override
    public int priority() { return 5; }
    
    @Override
    public String render(PromptContext ctx) {
        return """
            【工具使用规则】
            
            1. 优先使用工具
               - 凡是能通过工具获取的信息，都要用工具，不要凭记忆
               - 文件内容、目录结构、命令输出等，必须通过工具获取
               - 不要假设文件内容，先读再改
            
            2. 编辑文件前先读
               - 修改文件前，必须先读取文件内容
               - 确认文件的当前状态，再进行修改
               - 不要凭记忆修改文件
            
            3. 精确编辑
               - 使用 edit_file 工具时，oldString 必须精确匹配
               - 如果匹配不到，提供更多上下文
               - 不要用模糊匹配
            
            4. 注意安全
               - 执行命令前，考虑命令的安全性
               - 危险命令（rm、format 等）要谨慎
               - 不确定的话先问用户
            """;
    }
}
```

**为什么 "编辑前先读" 要反复强调？**

因为 LLM 经常犯这个错：

- 凭记忆改文件，结果文件早就不是那样了
- 改完发现不对，又改，越改越乱

明确要求 "编辑前必须先读"，能减少很多错误。

**双重强化：**

SystemConstraintsModule 里提了一次（"不要编造"），这里又专门讲一次。

重要的规则说两遍，LLM 更容易记住。

------

### 模块 6：ToneStyleModule（优先级 6）

语气和风格。

```java
// prompt/impl/ToneStyleModule.java
public class ToneStyleModule implements PromptModule {
    
    @Override
    public String name() { return "语气风格"; }
    
    @Override
    public int priority() { return 6; }
    
    @Override
    public String render(PromptContext ctx) {
        return """
            【回复风格】
            
            1. 用中文回复
               - 除非用户明确要求用其他语言
            
            2. 简洁明了
               - 不要废话，直接说重点
               - 长内容分点说明
               - 不要过度客套
            
            3. 专业但友好
               - 技术问题要专业准确
               - 语气要友好，不要太生硬
            
            4. 主动汇报进度
               - 正在做什么，告诉用户
               - 遇到什么问题，告诉用户
               - 完成了，告诉用户结果
            """;
    }
}
```

------

### 模块 7：TextOutputModule（优先级 7）

输出格式规范。

```java
// prompt/impl/TextOutputModule.java
public class TextOutputModule implements PromptModule {
    
    @Override
    public String name() { return "输出格式"; }
    
    @Override
    public int priority() { return 7; }
    
    @Override
    public String render(PromptContext ctx) {
        return """
            【输出格式规范】
            
            1. 代码用代码块
               - 代码用 ``` 包裹
               - 指定语言，如 ```java
               - 保持正确的缩进
            
            2. 文件内容引用
               - 引用文件内容时，用代码块
               - 标明文件名和行号
            
            3. 列表和分点
               - 多个项目用列表
               - 步骤用编号列表
            
            4. 重要信息突出
               - 重要的内容可以加粗
               - 警告用明显的标记
            """;
    }
}
```

------

## 第三步：PromptBuilder — 装配器

把所有模块组装起来，生成完整的 system prompt。

```java
// prompt/PromptBuilder.java
package com.rook.prompt;

import java.util.*;
import java.util.stream.Collectors;

public class PromptBuilder {
    
    private final List<PromptModule> modules = new ArrayList<>();
    
    /**
     * 注册一个模块
     */
    public PromptBuilder register(PromptModule module) {
        modules.add(module);
        return this;  // 链式调用
    }
    
    /**
     * 构建完整的 system prompt
     * 按优先级排序，逐个 render，用 \n\n 拼接
     */
    public String build(PromptContext ctx) {
        return modules.stream()
            // 按优先级排序（小的在前）
            .sorted(Comparator.comparingInt(PromptModule::priority))
            // 过滤掉渲染结果为空的模块
            .filter(m -> {
                String content = m.render(ctx);
                return content != null && !content.isBlank();
            })
            // 每个模块格式化为：## 名称\n内容
            .map(m -> "## " + m.name() + "\n" + m.render(ctx).strip())
            // 用 \n\n 拼接所有模块
            .collect(Collectors.joining("\n\n"));
    }
    
    /**
     * 获取模块数量
     */
    public int moduleCount() {
        return modules.size();
    }
}
```

### 逐段详解

#### 1. 注册模块

```java
public PromptBuilder register(PromptModule module) {
    modules.add(module);
    return this;
}
```

**链式调用：**

```java
PromptBuilder builder = new PromptBuilder()
    .register(new IdentityModule("0.9.0"))
    .register(new SystemConstraintsModule())
    .register(new TaskModeModule())
    .register(new ToolUsageModule())
    .register(new ToneStyleModule());
```

加一个模块就加一行 register，清晰。

#### 2. 构建 prompt

```java
public String build(PromptContext ctx) {
    return modules.stream()
        .sorted(Comparator.comparingInt(PromptModule::priority))
        .filter(m -> {
            String content = m.render(ctx);
            return content != null && !content.isBlank();
        })
        .map(m -> "## " + m.name() + "\n" + m.render(ctx).strip())
        .collect(Collectors.joining("\n\n"));
}
```

**四步：**

##### 第 1 步：排序

```
.sorted(Comparator.comparingInt(PromptModule::priority))
```

按优先级从小到大排，优先级小的在前。

##### 第 2 步：过滤空模块

```
.filter(m -> {
    String content = m.render(ctx);
    return content != null && !content.isBlank();
})
```

可选模块如果当前不需要输出，render 返回空字符串，就过滤掉。

**为什么要过滤？**

如果不过滤，会输出一个空的 section：

```
## 某个可选模块

（空的）
```

看起来很奇怪，也浪费 token。

##### 第 3 步：格式化每个模块

```
.map(m -> "## " + m.name() + "\n" + m.render(ctx).strip())
```

每个模块格式化为：

```
## 模块名称
模块内容
```

用 `##` 标题，结构清晰，LLM 也容易理解。

##### 第 4 步：拼接

```
.collect(Collectors.joining("\n\n"))
```

所有模块用空行隔开，拼成一整段。

------

## 第四步：PromptContext — 上下文

传递给模块的上下文信息。

```java
// prompt/PromptContext.java
package com.rook.prompt;

public record PromptContext(
    TaskMode mode,           // 当前模式
    String workingDir,       // 工作目录
    String language,         // 用户偏好语言
    boolean hasWritePermission, // 是否有写权限
    Map<String, String> extra  // 额外信息
) {
    public enum TaskMode {
        NORMAL,      // 正常模式
        PLAN_ONLY,   // 计划模式（只读）
        ASK_FIRST    // 确认模式
    }
    
    public static PromptContext defaultContext() {
        return new PromptContext(
            TaskMode.NORMAL,
            System.getProperty("user.dir"),
            "zh-CN",
            true,
            Map.of()
        );
    }
}
```

**为什么需要 Context？**

模块的输出可能依赖一些动态信息：

- 当前是什么模式？
- 用户用什么语言？
- 有没有写权限？
- 工作目录是什么？

把这些打包成 Context，传给每个模块的 render 方法。

**好处：**

- 模块不用自己去查这些信息
- 方便测试（可以构造假的 Context）
- 加新的上下文字段不用改模块接口

------

## 第五步：两通道机制 — 缓存 + 补充

### 为什么分两通道？

有些 LLM 支持 system prompt 缓存（比如 Anthropic 的 prompt caching）：

- 缓存的部分：不收费、速度快
- 没缓存的部分：正常收费、正常速度

**如果所有内容都放 system prompt：**

- 每次有一点变化（比如当前目录变了），整个缓存都失效
- 浪费钱，也慢

**解决方案：分两通道**

- **缓存通道**：稳定不变的内容 → 放 system 消息 → 可以缓存
- **补充通道**：变化的内容 → 放一条特殊的 user 消息 → 每次更新

### 缓存通道（System 消息）

放稳定的模块：

- IdentityModule — 版本不变就不变
- SystemConstraintsModule — 规则不变
- ToolUsageModule — 工具规则不变
- ToneStyleModule — 风格不变
- ...

**这些内容很少变，可以安全缓存。**

### 补充通道（Supplement 消息）

放变化的内容：

- 当前工作目录
- 当前时间
- 环境信息
- 会话特定的信息
- ...

**放在一条特殊的 user 消息里：**

```
[Supplement]
当前工作目录: /home/user/project
当前时间: 2026-08-09 15:30:00
环境: Java 17, Maven 3.9
...
```

**为什么放 user 消息？**

因为 system 消息缓存了，不能改。改了缓存就失效了。

变化的内容放 user 消息里，不影响 system 消息的缓存。

### 完整的消息结构

```
messages[0]  → system: 【缓存的 system prompt，稳定不变】
messages[1]  → user: 【Supplement 补充信息，每次更新】
messages[2]  → user: 【用户的实际问题】
messages[3]  → assistant: ...
messages[4]  → user: 【工具结果】
...
```

**缓存命中率：**

- 只要 system prompt 没变，缓存就有效
- Supplement 和对话历史不影响缓存
- 缓存命中率接近 100%

**节省：**

- 假设 system prompt 有 1000 token
- 每次调用都省 1000 token 的费用
- 速度也更快（缓存命中的部分不用重新处理）

------

## 完整示例

### 构建 Prompt

```java
// 初始化 Builder
PromptBuilder builder = new PromptBuilder()
    .register(new IdentityModule("0.9.0"))
    .register(new SystemConstraintsModule())
    .register(new TaskModeModule())
    .register(new ActionExecutionModule())
    .register(new ToolUsageModule())
    .register(new ToneStyleModule())
    .register(new TextOutputModule());

// 正常模式的上下文
PromptContext normalCtx = PromptContext.defaultContext();

// 生成 system prompt
String systemPrompt = builder.build(normalCtx);
```

### 生成的 Prompt 长什么样

```
## 身份
你是 Rook —— 一个命令行 AI 编程助手，版本 0.9.0。

你的职责是帮助用户完成编程相关的任务：
- 阅读和理解代码
- 修改和编写代码
- ...

## 基本约束
【重要规则】

1. 不要编造信息
   - 不确定的内容，用工具去查，不要猜
   - ...

## 任务模式
当前模式：正常模式

你可以自由使用所有工具...

## 行动执行
【行动执行流程】
1. 思考需要做什么
   ...

## 工具使用规则
【工具使用规则】
1. 优先使用工具
   ...

## 语气风格
【回复风格】
1. 用中文回复
   ...

## 输出格式
【输出格式规范】
1. 代码用代码块
   ...
```

### 切换到计划模式

```
// 计划模式的上下文
PromptContext planCtx = new PromptContext(
    TaskMode.PLAN_ONLY,
    "/home/user/project",
    "zh-CN",
    false,
    Map.of()
);

String planPrompt = builder.build(planCtx);
```

**只有 TaskModeModule 的输出变了，其他模块都一样。**

------

## 运行验证

### 测试 1：正常模式

```
@Test
void should_build_normal_mode_prompt() {
    PromptBuilder builder = new PromptBuilder()
        .register(new IdentityModule("0.9.0"))
        .register(new TaskModeModule());
    
    PromptContext ctx = PromptContext.defaultContext();
    String prompt = builder.build(ctx);
    
    assertTrue(prompt.contains("正常模式"));
    assertTrue(prompt.contains("Rook"));
    assertTrue(prompt.contains("## 身份"));
    assertTrue(prompt.contains("## 任务模式"));
}
```

### 测试 2：计划模式

```
@Test
void should_build_plan_mode_prompt() {
    PromptBuilder builder = new PromptBuilder()
        .register(new IdentityModule("0.9.0"))
        .register(new TaskModeModule());
    
    PromptContext ctx = new PromptContext(
        TaskMode.PLAN_ONLY,
        "/tmp",
        "zh-CN",
        false,
        Map.of()
    );
    
    String prompt = builder.build(ctx);
    
    assertTrue(prompt.contains("计划模式"));
    assertTrue(prompt.contains("只能分析和给出建议"));
}
```

### 测试 3：优先级排序

```
@Test
void should_sort_by_priority() {
    PromptBuilder builder = new PromptBuilder()
        .register(new ToolUsageModule())    // 优先级 5
        .register(new IdentityModule("1.0")); // 优先级 1
    
    String prompt = builder.build(PromptContext.defaultContext());
    
    // 身份（优先级 1）应该在工具使用（优先级 5）前面
    int identityPos = prompt.indexOf("## 身份");
    int toolPos = prompt.indexOf("## 工具使用规则");
    assertTrue(identityPos < toolPos);
}
```

------

## 常见坑（详细版）

### 1. 缓存失效

**现象：** 明明大部分内容没变，缓存却总是不命中。

**原因：** 把变化的内容也放在 system prompt 里了。

**比如：**

- 当前时间
- 当前目录
- 会话 ID
- 用户名字

这些每次都变，放在 system prompt 里，整个缓存就失效了。

**解决：**

- 稳定的放 system（缓存通道）
- 变化的放 Supplement（补充通道）
- 缓存通道的内容尽量保持不变

### 2. 优先级写错

**现象：** 模块的顺序不对，重要的内容跑到后面去了。

**原因：** 优先级数字写错了。

**常见错误：**

- 新模块的优先级写得太小，插到最前面了
- 可选模块的优先级写得比固定模块还小
- 两个模块优先级一样，顺序不确定

**解决：**

- 严格遵守优先级区间约定（1-70 固定，71-100 可选）
- 每个模块的优先级要仔细考虑
- 不要两个模块优先级一样（排序不稳定）

### 3. 空模块输出空标题

**现象：** prompt 里出现空的 section：

```
## 某个模块

（下面是空的）
```

**原因：** 可选模块 render 返回空字符串，但 Builder 没过滤。

**解决：**

Builder 里加 filter，把 render 返回空的模块过滤掉：

```
.filter(m -> {
    String content = m.render(ctx);
    return content != null && !content.isBlank();
})
```

### 4. 模块之间内容重复

**现象：** 不同模块里说了差不多的话，浪费 token。

**原因：** 模块职责划分不清。

**解决：**

- 每个模块职责单一
- 重要的规则可以在多个模块提（双重强化）
- 但不要完全重复
- 定期 review 模块内容，合并重复的

### 5. Prompt 太长

**现象：** system prompt 越来越长，token 用得多，速度也慢。

**原因：** 模块越加越多，每个模块写得越来越详细。

**解决：**

- 简洁是美德，能少说就少说
- 不重要的内容可以删掉
- 可选模块默认不注册，需要时再加
- 定期精简，去掉没用的

### 6. 模块依赖顺序

**现象：** 某个模块的内容依赖另一个模块的输出，但顺序反了。

**原因：** 优先级设置不对。

**解决：**

- 设计模块时考虑依赖关系
- 被依赖的模块优先级更高（更靠前）
- 尽量减少模块之间的依赖

------

## 架构图

```
┌─────────────────────────────────────────────────────────────┐
│                        PromptBuilder                         │
│                                                             │
│  modules: [Module1, Module2, Module3, ...]                  │
│                                                             │
│         │                                                   │
│         ▼                                                   │
│  1. 按 priority 排序                                        │
│         │                                                   │
│         ▼                                                   │
│  2. 过滤空模块                                               │
│         │                                                   │
│         ▼                                                   │
│  3. 逐个 render(ctx)                                        │
│         │                                                   │
│         ▼                                                   │
│  4. \n\n 拼接                                               │
│         │                                                   │
│         ▼                                                   │
│  完整的 System Prompt                                       │
└─────────────────────────────────────────────────────────────┘
                              │
          ┌───────────────────┴───────────────────┐
          │                                       │
          ▼                                       ▼
┌───────────────────┐                   ┌───────────────────┐
│   缓存通道         │                   │   补充通道         │
│                   │                   │                   │
│ System 消息       │                   │ Supplement 消息    │
│ 稳定不变的内容     │                   │ 变化的内容         │
│ 可缓存            │                   │ 每次更新           │
│                   │                   │                   │
│ IdentityModule    │                   │ 当前目录           │
│ SystemConstraints │                   │ 当前时间           │
│ ToolUsageModule   │                   │ 环境信息           │
│ ...               │                   │ ...                │
└───────────────────┘                   └───────────────────┘
```

------

## 下一章

[12 - 权限黑名单与沙箱.md](./12-权限黑名单与沙箱.md) — 黑名单拦截危险命令 + 沙箱限制文件操作范围
