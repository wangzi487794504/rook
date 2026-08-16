# 21 — Hook 引擎：生命周期自动化

## 这一章做什么

在 Agent 生命周期的 **11 个关键节点**上挂自动化动作。pre_tool 拦截危险操作，session_start 注入通知，post_tool 记日志 —— 全是声明式 YAML 配置，不用改 Java 代码。

**完成后你能做到：**

- 想拦截 `rm -rf`？写几行 YAML，不用改工具代码
- 想每次启动自动检查更新？配个 session_start hook
- 想工具执行后自动记日志？配个 post_tool hook
- 运维策略和业务代码彻底分离

## 为什么需要

### 没有 Hook 的问题

想加一个 "拦截 rm -rf 命令" 的功能，要怎么做？

```
// 旧方式：改 ExecCommandTool 的代码
public ToolResult execute(Map<String, Object> params) {
    String command = (String) params.get("command");
    
    // 硬编码的校验
    if (command.contains("rm -rf")) {
        return ToolResult.error("危险命令被拦截");
    }
    if (command.contains("mkfs")) {
        return ToolResult.error("危险命令被拦截");
    }
    // ... 更多硬编码
    
    // 执行命令...
}
```

**问题：**

|  问题  |                      说明                      |
| :----: | :--------------------------------------------: |
| 改代码 |      每次加规则都要改 Java 代码、重新编译      |
|  耦合  |           安全策略和业务逻辑混在一起           |
| 不灵活 | 不同项目、不同用户的规则不一样，硬编码没法定制 |
| 难测试 |       规则散落在各个工具里，不好统一测试       |
| 难维护 |         规则多了，代码里到处是 if 判断         |

### 有 Hook 之后

```
# .rook/hooks.yaml — 不用改一行 Java
hooks:
  - name: block-dangerous-commands
    event: PRE_TOOL_USE
    if:
      all_of:
        - field: tool
          exact: exec_command
        - field: command
          regex: "rm -rf|mkfs|dd if="
    action:
      type: command
      command: "echo '危险命令被拦截' >&2 && exit 2"
```

**优势：**

- 声明式配置，不用改代码
- 规则集中管理，清晰
- 项目级、用户级可以有不同的规则
- 热更新（改 YAML 不用重启）

## 核心概念

### 11 个生命周期事件

|         事件         |       触发时机       |                 用途                 |
| :------------------: | :------------------: | :----------------------------------: |
|   `SESSION_START`    |      会话开始时      | 注入欢迎信息、检查更新、加载项目说明 |
|    `SESSION_END`     |      会话结束时      |    记日志、清理临时文件、统计信息    |
|    `ROUND_START`     | 每轮 Agent Loop 开始 |           记日志、检查状态           |
|     `ROUND_END`      | 每轮 Agent Loop 结束 |       统计 token、触发记忆提取       |
|    `PRE_LLM_CALL`    |     调 LLM 之前      | 修改 prompt、注入额外上下文、记日志  |
|   `POST_LLM_CALL`    |     调 LLM 之后      |     记日志、统计 token、缓存结果     |
|    `PRE_TOOL_USE`    |     工具执行之前     |   拦截危险操作、参数校验、权限检查   |
|   `POST_TOOL_USE`    |     工具执行之后     |        记日志、结果处理、通知        |
| `USER_PROMPT_SUBMIT` |    用户提交消息时    |    内容过滤、敏感词检测、自动补全    |
|     `TOOL_ERROR`     |    工具执行出错时    |          错误上报、自动重试          |
|    `COMPRESSION`     |     上下文压缩时     |        记日志、备份原始上下文        |

### 条件匹配

四种匹配模式：

- `exact`：精确匹配
- `not`：不等于
- `regex`：正则匹配（部分匹配，用 find ()）
- `glob`：通配符匹配

逻辑组合：

- `all_of`：所有条件都满足（AND）
- `any_of`：任一条件满足（OR）

### 四种动作类型

|    动作    |        含义        |             示例             |
| :--------: | :----------------: | :--------------------------: |
| `command`  |  执行 shell 命令   |  拦截、记日志、调用外部脚本  |
|  `prompt`  |     注入提示词     | 注入额外上下文、修改用户输入 |
|   `http`   |    发 HTTP 请求    |   通知、上报、调用 webhook   |
| `subagent` | 调子 Agent（占位） |     复杂处理（后续扩展）     |

### 拦截信号

- `pre_tool` hook 的 command 动作，exit code = 2 表示拦截
- stderr 的内容作为拒绝原因
- 拦截后，工具不执行，直接返回错误结果

------

## 第一步：Hook YAML 格式详解

```
# .rook/hooks.yaml
hooks:
  # ── 示例 1：拦截危险命令 ──
  - name: block-dangerous-commands
    description: "拦截 rm -rf 等危险命令"
    event: PRE_TOOL_USE
    if:
      all_of:
        - field: tool
          exact: exec_command
        - field: command
          regex: "rm -rf|mkfs|dd if=|> /dev/sd"
    action:
      type: command
      command: "echo 'BLOCKED: 危险命令，已被 Hook 拦截' >&2 && exit 2"

  # ── 示例 2：会话开始注入项目说明 ──
  - name: inject-project-readme
    description: "会话开始时注入项目说明"
    event: SESSION_START
    if:
      - field: file_exists
        glob: "./README.md"
    action:
      type: prompt
      prompt: |
        项目说明已加载，请先阅读 README.md 了解项目结构。

  # ── 示例 3：工具执行后记日志 ──
  - name: log-tool-usage
    description: "记录所有工具调用"
    event: POST_TOOL_USE
    action:
      type: command
      command: "echo '[$(date)] $ROOK_TOOL $ROOK_TOOL_RESULT' >> ~/.rook/tool.log"

  # ── 示例 4：错误上报 ──
  - name: report-errors
    description: "工具错误时上报到 webhook"
    event: TOOL_ERROR
    action:
      type: http
      url: "https://example.com/webhook"
      method: POST
      headers:
        Content-Type: "application/json"
      body: '{"tool":"$ROOK_TOOL","error":"$ROOK_ERROR"}'
```

### 字段详解

|     字段      | 必填 |            含义            |
| :-----------: | :--: | :------------------------: |
|    `name`     |  ✅   |       Hook 唯一标识        |
| `description` |  ❌   |       描述，便于阅读       |
|    `event`    |  ✅   |  触发的事件（11 种之一）   |
|     `if`      |  ❌   |    条件，不填则总是触发    |
|   `action`    |  ✅   |        要执行的动作        |
|    `async`    |  ❌   | 是否异步执行（默认 false） |

### 条件字段详解

```
if:
  all_of:
    - field: tool        # 要匹配的字段
      exact: exec_command # 匹配模式和值
    - field: command
      regex: "rm -rf"
```

**可用字段（不同事件字段不同）：**

|             事件             |                           可用字段                           |
| :--------------------------: | :----------------------------------------------------------: |
| PRE_TOOL_USE / POST_TOOL_USE | `tool`、`command`（exec_command 时）、`path`（文件工具时）、`input` |
| PRE_LLM_CALL / POST_LLM_CALL |          `model`、`prompt_length`、`message_count`           |
|     SESSION_START / END      |                 `session_id`、`file_exists`                  |
|      USER_PROMPT_SUBMIT      |                     `content`、`length`                      |
|          TOOL_ERROR          |                       `tool`、`error`                        |

### 环境变量注入

动作执行时，会注入以下环境变量：

|        变量        |          含义          |
| :----------------: | :--------------------: |
|    `ROOK_EVENT`    |         事件名         |
|    `ROOK_TOOL`     | 工具名（工具相关事件） |
| `ROOK_TOOL_INPUT`  |     工具输入 JSON      |
| `ROOK_TOOL_RESULT` | 工具结果（POST 事件）  |
|    `ROOK_ERROR`    | 错误信息（TOOL_ERROR） |
| `ROOK_SESSION_ID`  |        会话 ID         |
| `ROOK_WORKING_DIR` |        工作目录        |

------

## 第二步：HookRule — 规则数据结构

```
// hook/HookRule.java
package com.rook.hook;

import java.util.List;
import java.util.Map;

public record HookRule(
    String name,
    String description,
    HookEvent event,
    HookCondition condition,   // 条件（null 表示总是触发）
    HookAction action,
    boolean async
) {}

// ── 条件 ──
public sealed interface HookCondition 
    permits HookCondition.AllOf, HookCondition.AnyOf, HookCondition.Matcher {
    
    boolean evaluate(HookContext ctx);

    record AllOf(List<HookCondition> conditions) implements HookCondition {
        @Override
        public boolean evaluate(HookContext ctx) {
            return conditions.stream().allMatch(c -> c.evaluate(ctx));
        }
    }

    record AnyOf(List<HookCondition> conditions) implements HookCondition {
        @Override
        public boolean evaluate(HookContext ctx) {
            return conditions.stream().anyMatch(c -> c.evaluate(ctx));
        }
    }

    record Matcher(String field, MatchMode mode, String value) implements HookCondition {
        @Override
        public boolean evaluate(HookContext ctx) {
            String actual = ctx.getField(field);
            if (actual == null) return false;
            return mode.match(actual, value);
        }
    }
}

// ── 匹配模式 ──
public enum MatchMode {
    EXACT {
        @Override
        public boolean match(String actual, String expected) {
            return actual.equals(expected);
        }
    },
    NOT {
        @Override
        public boolean match(String actual, String expected) {
            return !actual.equals(expected);
        }
    },
    REGEX {
        @Override
        public boolean match(String actual, String expected) {
            // ★ 用 find() 不是 matches()，部分匹配
            return java.util.regex.Pattern.compile(expected)
                .matcher(actual).find();
        }
    },
    GLOB {
        @Override
        public boolean match(String actual, String expected) {
            // glob 转正则
            String regex = expected
                .replace(".", "\\.")
                .replace("*", "[^/]*")
                .replace("?", "[^/]")
                .replace("**", ".*");
            return actual.matches(regex);
        }
    };

    public abstract boolean match(String actual, String expected);
}
```

### 逐段详解

#### 1. sealed interface 条件

```
public sealed interface HookCondition 
    permits HookCondition.AllOf, HookCondition.AnyOf, HookCondition.Matcher {
```

**用 Java 17 的 sealed interface，限制只有三种实现：**

- `AllOf`：AND，所有条件都满足
- `AnyOf`：OR，任一条件满足
- `Matcher`：单个匹配器

**sealed 的好处：**

- 编译器知道所有可能的子类型
- switch 表达式可以穷举，不用 default
- 防止意外的子类型

#### 2. AllOf / AnyOf

```
record AllOf(List<HookCondition> conditions) implements HookCondition {
    @Override
    public boolean evaluate(HookContext ctx) {
        return conditions.stream().allMatch(c -> c.evaluate(ctx));
    }
}
```

**递归结构，可以嵌套：**

```
if:
  all_of:
    - field: tool
      exact: exec_command
    - any_of:
        - field: command
          regex: "rm -rf"
        - field: command
          regex: "mkfs"
```

#### 3. REGEX 用 find () 不是 matches ()

```
REGEX {
    @Override
    public boolean match(String actual, String expected) {
        return Pattern.compile(expected).matcher(actual).find();
    }
}
```

**★ 这是个常见坑。**

- `matches()`：整个字符串完全匹配正则
- `find()`：字符串中包含匹配的部分就行

**比如：**

- 命令是 `sudo rm -rf /tmp`
- 正则是 `rm -rf`
- `matches()` → false（整个字符串不是 "rm -rf"）
- `find()` → true（包含 "rm -rf"）

**我们要的是部分匹配，所以用 find ()。**

------

## 第三步：HookAction — 四种动作

```
// hook/HookAction.java
package com.rook.hook;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public sealed interface HookAction 
    permits HookAction.CommandAction, HookAction.PromptAction, 
            HookAction.HttpAction, HookAction.SubagentAction {
    
    HookResult execute(HookContext ctx);

    // ── 1. 执行命令 ──
    record CommandAction(String command) implements HookAction {
        @Override
        public HookResult execute(HookContext ctx) {
            try {
                ProcessBuilder pb = new ProcessBuilder();
                if (isWindows()) {
                    pb.command("cmd", "/c", command);
                } else {
                    pb.command("sh", "-c", command);
                }
                
                // 注入环境变量
                Map<String, String> env = pb.environment();
                env.putAll(ctx.getEnvVars());
                
                pb.redirectErrorStream(false);
                Process p = pb.start();
                
                // 读取 stdout 和 stderr
                String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                
                int exitCode = p.waitFor();
                
                // ★ exit code 2 = 拦截
                if (exitCode == 2) {
                    return HookResult.intercept(stderr.isBlank() ? stdout : stderr);
                }
                
                return HookResult.ok(stdout);
                
            } catch (Exception e) {
                // Hook 自身失败不中断 Agent
                System.err.println("[Hook] 命令执行失败: " + e.getMessage());
                return HookResult.ok("");
            }
        }
        
        private boolean isWindows() {
            return System.getProperty("os.name").toLowerCase().contains("win");
        }
    }

    // ── 2. 注入提示词 ──
    record PromptAction(String prompt) implements HookAction {
        @Override
        public HookResult execute(HookContext ctx) {
            // 替换环境变量占位符
            String resolved = ctx.resolveVars(prompt);
            return HookResult.prompt(resolved);
        }
    }

    // ── 3. HTTP 请求 ──
    record HttpAction(
            String url,
            String method,
            Map<String, String> headers,
            String body
    ) implements HookAction {
        
        private static final HttpClient HTTP = HttpClient.newHttpClient();
        
        @Override
        public HookResult execute(HookContext ctx) {
            try {
                String resolvedBody = ctx.resolveVars(body);
                
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .method(method, HttpRequest.BodyPublishers.ofString(resolvedBody));
                
                if (headers != null) {
                    headers.forEach(reqBuilder::header);
                }
                
                HttpResponse<String> resp = HTTP.send(
                    reqBuilder.build(), 
                    HttpResponse.BodyHandlers.ofString()
                );
                
                return HookResult.ok(resp.body());
                
            } catch (Exception e) {
                System.err.println("[Hook] HTTP 请求失败: " + e.getMessage());
                return HookResult.ok("");
            }
        }
    }

    // ── 4. 子 Agent（占位，后续扩展） ──
    record SubagentAction(String agentType, String prompt) implements HookAction {
        @Override
        public HookResult execute(HookContext ctx) {
            System.out.println("[Hook] Subagent 动作暂未实现");
            return HookResult.ok("");
        }
    }
}

// ── 动作执行结果 ──
public record HookResult(
    boolean intercept,   // 是否拦截（pre_tool 用）
    String message,      // 拦截原因或输出
    String promptInject  // 要注入的提示词
) {
    public static HookResult ok(String message) {
        return new HookResult(false, message, null);
    }
    
    public static HookResult intercept(String reason) {
        return new HookResult(true, reason, null);
    }
    
    public static HookResult prompt(String prompt) {
        return new HookResult(false, null, prompt);
    }
}
```

### 逐段详解

#### 1. CommandAction — 执行命令

```
record CommandAction(String command) implements HookAction {
    @Override
    public HookResult execute(HookContext ctx) {
        ProcessBuilder pb = new ProcessBuilder();
        if (isWindows()) {
            pb.command("cmd", "/c", command);
        } else {
            pb.command("sh", "-c", command);
        }
        // 注入环境变量
        // 执行
        // 读取 stdout/stderr
        // exit code 2 = 拦截
    }
}
```

**关键点：**

##### 套一层 shell

和 ExecCommandTool 一样，用 `sh -c` 或 `cmd /c` 包装，支持管道、重定向等。

##### 注入环境变量

```
Map<String, String> env = pb.environment();
env.putAll(ctx.getEnvVars());
```

把 ROOK_TOOL、ROOK_EVENT 等变量注入，命令里可以用 `$ROOK_TOOL`。

##### exit code 2 = 拦截

```
if (exitCode == 2) {
    return HookResult.intercept(stderr.isBlank() ? stdout : stderr);
}
```

**约定：**

- exit 0：成功，不拦截
- exit 1：一般错误，不拦截（记日志）
- exit 2：拦截，stderr 是拒绝原因

**为什么用 exit code 2？**

- 0 和 1 是 shell 常用的（成功 / 一般错误）
- 2 比较少用，专门用来表示 "拦截"
- 避免和命令本身的正常退出码冲突

##### stderr 作为拒绝原因

```
return HookResult.intercept(stderr.isBlank() ? stdout : stderr);
```

约定拒绝原因写在 stderr，这样 stdout 可以用来输出其他信息。

如果 stderr 为空，就用 stdout。

#### 2. PromptAction — 注入提示词

```
record PromptAction(String prompt) implements HookAction {
    @Override
    public HookResult execute(HookContext ctx) {
        String resolved = ctx.resolveVars(prompt);
        return HookResult.prompt(resolved);
    }
}
```

**用于：**

- SESSION_START：注入项目说明
- PRE_LLM_CALL：注入额外上下文
- USER_PROMPT_SUBMIT：修改用户输入

#### 3. HttpAction — HTTP 请求

```
record HttpAction(String url, String method, Map<String, String> headers, String body) {
    @Override
    public HookResult execute(HookContext ctx) {
        // 用 Java 11 HttpClient
        // body 里的 $ROOK_XXX 变量会被替换
    }
}
```

**用于：**

- 错误上报到 webhook
- 通知到 Slack / 钉钉
- 调用外部 API

#### 4. 失败静默

```
} catch (Exception e) {
    System.err.println("[Hook] 命令执行失败: " + e.getMessage());
    return HookResult.ok("");
}
```

**★ Hook 自身失败不中断 Agent。**

Hook 是 "锦上添花" 的，不能因为 Hook 报错就整个 Agent 崩了。

- 记日志
- 返回 ok（不拦截）
- 主流程继续

------

## 第四步：HookEngine — 事件分发

```
// hook/HookEngine.java
package com.rook.hook;

import org.yaml.snakeyaml.Yaml;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class HookEngine {

    private final List<HookRule> rules = new ArrayList<>();

    /**
     * 从 YAML 文件加载规则
     */
    public void loadFromFile(Path file) throws IOException {
        if (!Files.exists(file)) {
            return;
        }

        String content = Files.readString(file);
        Map<String, Object> yaml = new Yaml().load(content);
        
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hooks = (List<Map<String, Object>>) yaml.get("hooks");
        
        if (hooks == null) return;

        for (Map<String, Object> h : hooks) {
            try {
                HookRule rule = parseRule(h);
                rules.add(rule);
            } catch (Exception e) {
                System.err.println("[Hook] 解析规则失败: " + e.getMessage());
            }
        }

        // ★ 加载期校验：拦截类事件不能 async
        validateAsyncRules();
    }

    /**
     * 触发事件
     * @param event 事件类型
     * @param ctx 上下文
     * @return 拦截原因（非 null 表示被拦截），或注入的提示词
     */
    public HookResult fire(HookEvent event, HookContext ctx) {
        for (HookRule rule : rules) {
            // 事件不匹配，跳过
            if (rule.event() != event) continue;

            // 条件不满足，跳过
            if (rule.condition() != null && !rule.condition().evaluate(ctx)) {
                continue;
            }

            try {
                if (rule.async()) {
                    // 异步执行，不等待结果
                    CompletableFuture.runAsync(() -> {
                        try {
                            rule.action().execute(ctx);
                        } catch (Exception e) {
                            System.err.println("[Hook] 异步执行失败: " + e.getMessage());
                        }
                    });
                    continue;
                }

                // 同步执行
                HookResult result = rule.action().execute(ctx);
                
                // 拦截？直接返回
                if (result.intercept()) {
                    return result;
                }
                
                // 有提示词注入？返回
                if (result.promptInject() != null) {
                    return result;
                }

            } catch (Exception e) {
                System.err.println("[Hook] 规则执行失败: " + rule.name() 
                    + " - " + e.getMessage());
            }
        }

        return HookResult.ok(null);
    }

    /**
     * 校验：拦截类事件（PRE_TOOL_USE、USER_PROMPT_SUBMIT）不能 async
     */
    private void validateAsyncRules() {
        for (HookRule rule : rules) {
            if (rule.async() && isInterceptEvent(rule.event())) {
                throw new IllegalStateException(
                    "Hook '" + rule.name() + "'：" + rule.event() 
                    + " 是拦截类事件，不能设置 async=true");
            }
        }
    }

    private boolean isInterceptEvent(HookEvent event) {
        return event == HookEvent.PRE_TOOL_USE 
            || event == HookEvent.USER_PROMPT_SUBMIT
            || event == HookEvent.PRE_LLM_CALL;
    }

    public List<HookRule> getRules() {
        return Collections.unmodifiableList(rules);
    }

    // ── YAML 解析（省略部分细节） ──
    private HookRule parseRule(Map<String, Object> h) {
        // 解析 name, event, condition, action...
        // ...
    }
}
```

### 逐段详解

#### 1. 加载 + 校验

```
public void loadFromFile(Path file) {
    // 解析 YAML
    // 逐条解析规则
    // ★ 加载期校验：拦截类事件不能 async
    validateAsyncRules();
}
```

**加载时就校验，有问题启动时报错，不让运行时才发现。**

#### 2. fire — 事件分发

```
public HookResult fire(HookEvent event, HookContext ctx) {
    for (HookRule rule : rules) {
        if (rule.event() != event) continue;
        if (rule.condition() != null && !rule.condition().evaluate(ctx)) continue;
        
        if (rule.async()) {
            // 异步，不等待
            CompletableFuture.runAsync(() -> rule.action().execute(ctx));
            continue;
        }
        
        HookResult result = rule.action().execute(ctx);
        if (result.intercept()) return result;  // 拦截，直接返回
        if (result.promptInject() != null) return result;
    }
    return HookResult.ok(null);
}
```

**流程：**

1. 遍历所有规则
2. 事件不匹配 → 跳过
3. 条件不满足 → 跳过
4. 异步 → 后台执行，不等待
5. 同步 → 执行
6. 拦截？→ 直接返回（后续规则不执行）
7. 有提示词注入？→ 返回
8. 都没有 → 继续下一个规则

**拦截是短路的：** 第一个拦截的规则直接返回，后面的规则不执行。

#### 3. 拦截类事件禁 async

```
private void validateAsyncRules() {
    for (HookRule rule : rules) {
        if (rule.async() && isInterceptEvent(rule.event())) {
            throw new IllegalStateException(...);
        }
    }
}
```

**为什么拦截类事件不能异步？**

- 拦截需要同步等待结果，才能决定要不要继续
- 异步的话，工具已经执行完了，拦截就没意义了
- 所以 PRE_TOOL_USE、USER_PROMPT_SUBMIT 等必须同步

**加载期校验，启动时就报错，不让运行时出问题。**

------

## 第五步：集成到各个生命周期

### 1. PRE_TOOL_USE — 工具执行前拦截

```
// tools/ToolExecutor.java
private ToolResult executeOne(ToolCall call) {
    // ★ Hook: PRE_TOOL_USE（在权限检查之前）
    if (hookEngine != null) {
        HookContext ctx = HookContext.forTool(call, null);
        HookResult result = hookEngine.fire(HookEvent.PRE_TOOL_USE, ctx);
        if (result.intercept()) {
            return ToolResult.error("HOOK_INTERCEPT: " + result.message());
        }
    }

    // 权限检查（第 12/13 章的五层权限）
    PermissionResult perm = permissionEngine.check(call);
    if (!perm.allowed()) {
        return ToolResult.error("PERMISSION_DENIED: " + perm.reason());
    }

    // 执行工具
    Tool tool = toolRegistry.find(call.name());
    return tool.execute(call.input());
}
```

**Hook 在权限检查之前：**

- Hook 是项目 / 用户自定义的策略
- 权限是系统级的安全网
- Hook 先拦，拦不住的再走权限
- 两层防护

### 2. POST_TOOL_USE — 工具执行后

```
// tools/ToolExecutor.java
private ToolResult executeOne(ToolCall call) {
    // ... PRE_TOOL_USE ...
    // ... 权限检查 ...
    
    ToolResult result = tool.execute(call.input());

    // ★ Hook: POST_TOOL_USE
    if (hookEngine != null) {
        HookContext ctx = HookContext.forTool(call, result);
        hookEngine.fire(HookEvent.POST_TOOL_USE, ctx);
        // POST 事件的结果一般不影响主流程（记日志、通知）
    }

    return result;
}
```

### 3. SESSION_START — 会话开始

```
// session/Session.java
public Session start() {
    // ★ Hook: SESSION_START
    if (hookEngine != null) {
        HookContext ctx = HookContext.forSession(this);
        HookResult result = hookEngine.fire(HookEvent.SESSION_START, ctx);
        if (result.promptInject() != null) {
            // 注入提示词到历史
            history.add(new ChatMessage("user", result.promptInject()));
        }
    }
    return this;
}
```

### 4. PRE_LLM_CALL — 调 LLM 之前

```
// core/agent/AgentLoop.java
while (state == RUNNING) {
    // ★ Hook: PRE_LLM_CALL
    if (hookEngine != null) {
        HookContext ctx = HookContext.forLlm(history, tools);
        HookResult result = hookEngine.fire(HookEvent.PRE_LLM_CALL, ctx);
        if (result.promptInject() != null) {
            // 注入额外上下文
            history.add(new ChatMessage("user", result.promptInject()));
        }
    }

    provider.stream(history, tools, collector);
    // ...
}
```

### 5. USER_PROMPT_SUBMIT — 用户提交消息

```
// cli/Repl.java
private void processMessage(String input) {
    // ★ Hook: USER_PROMPT_SUBMIT
    if (hookEngine != null) {
        HookContext ctx = HookContext.forUserPrompt(input);
        HookResult result = hookEngine.fire(HookEvent.USER_PROMPT_SUBMIT, ctx);
        if (result.intercept()) {
            console.printError("消息被拦截: " + result.message());
            return;
        }
        if (result.promptInject() != null) {
            input = result.promptInject();  // 替换用户输入
        }
    }

    history.add(new ChatMessage("user", input));
    agentLoop.run();
}
```

------

## 完整流程

### PRE_TOOL_USE 拦截流程

```
LLM 决定调用 exec_command("rm -rf /tmp")
    │
    ▼
ToolExecutor.executeOne(call)
    │
    ├─ ★ Hook: PRE_TOOL_USE
    │   ├─ 遍历规则
    │   ├─ 找到 block-dangerous-commands
    │   ├─ 条件匹配：
    │   │   ├─ tool == "exec_command" ✓
    │   │   └─ command 包含 "rm -rf" ✓
    │   ├─ 执行动作：
    │   │   └─ sh -c "echo 'BLOCKED' >&2 && exit 2"
    │   ├─ exit code = 2 → 拦截
    │   └─ 返回拦截原因："BLOCKED"
    │
    ├─ 被拦截 → 直接返回 ToolResult.error
    │
    ▼
tool_result: HOOK_INTERCEPT: BLOCKED
    │
    ▼
回灌给 LLM → LLM 知道被拦截了，调整策略
```

### SESSION_START 注入流程

```
用户启动 Rook
    │
    ▼
Session.start()
    │
    ├─ ★ Hook: SESSION_START
    │   ├─ 找到 inject-project-readme
    │   ├─ 条件：README.md 存在 ✓
    │   ├─ 执行动作：prompt 注入
    │   └─ 返回提示词："项目说明已加载..."
    │
    ├─ 提示词注入到 history
    │
    ▼
用户开始对话，AI 已经知道项目说明
```

------

## 运行验证

### 测试 1：拦截危险命令

```
> 帮我执行 rm -rf /tmp/test

[调用工具: exec_command("rm -rf /tmp/test")]
[HOOK_INTERCEPT: BLOCKED: 危险命令，已被 Hook 拦截]

（LLM 收到拦截结果，可能会换一种方式）
```

### 测试 2：查看 Hook 规则

```
> :hooks
已加载 4 个 Hook 规则：

  1. block-dangerous-commands  [PRE_TOOL_USE]  拦截 rm -rf 等危险命令
  2. inject-project-readme     [SESSION_START] 会话开始时注入项目说明
  3. log-tool-usage            [POST_TOOL_USE] 记录所有工具调用
  4. report-errors             [TOOL_ERROR]    工具错误时上报到 webhook
```

### 测试 3：会话开始注入

```
> rook

[Hook] SESSION_START 触发: inject-project-readme
项目说明已加载，请先阅读 README.md 了解项目结构。

> 你好
你好！我看到这是一个 Java 项目，使用 Maven 构建...
```

### 测试 4：工具日志

```
> 帮我读一下 pom.xml

（工具执行后）

$ cat ~/.rook/tool.log
[2026-08-13 15:30:45] read_file success
[2026-08-13 15:31:00] exec_command success
```

------

## 常见坑（详细版）

### 1. 拦截类事件用了 async

**现象：** PRE_TOOL_USE 的 Hook 配了 async=true，但拦截不生效。

**原因：** 异步执行，工具已经执行完了，拦截结果才回来，没用。

**解决：**

- 加载期校验，拦截类事件不能 async，启动直接报错
- PRE_TOOL_USE、USER_PROMPT_SUBMIT、PRE_LLM_CALL 必须同步

### 2. regex 用 matches () 不用 find ()

**现象：** 正则 `rm -rf` 匹配不到 `sudo rm -rf /tmp`。

**原因：** `matches()` 要求整个字符串完全匹配，`find()` 是部分匹配。

**解决：**

- 用 `find()`，命令中包含关键词就算匹配
- 这是最常见的坑，一定要注意

### 3. Hook 自身失败中断 Agent

**现象：** Hook 的命令报错了，整个 Agent 崩了。

**原因：** 没有 catch 住 Hook 执行的异常。

**解决：**

- Hook 执行都包在 try-catch 里
- 失败只记日志，返回 ok（不拦截）
- Hook 是辅助功能，不能影响主流程

### 4. exit code 冲突

**现象：** 命令本身正常退出码是 2，被误判为拦截。

**原因：** 我们约定 exit 2 = 拦截，但有些命令正常退出码也是 2。

**解决：**

- 这是约定，Hook 脚本里要用 `exit 2` 表示拦截
- 正常命令的退出码 2 比较少见，影响不大
- 或者用更明确的方式：输出特定标记 `__ROOK_INTERCEPT__`

### 5. 环境变量没注入

**现象：** Hook 命令里用 `$ROOK_TOOL`，但是空的。

**原因：** 忘记把上下文变量注入到 ProcessBuilder 的环境里。

**解决：**

- 执行命令前，`pb.environment().putAll(ctx.getEnvVars())`
- Windows 下变量名和引用方式可能不同（% ROOK_TOOL%）

### 6. 条件字段名不对

**现象：** Hook 条件写了但不触发。

**原因：** 字段名和上下文中的不一致，比如写了 `cmd` 但实际是 `command`。

**解决：**

- 文档里明确每个事件的可用字段
- 或者 HookContext 里对未知字段打警告日志
- 加个 `:hooks debug` 命令，显示每个规则的匹配情况

### 7. YAML 缩进错误

**现象：** Hook 加载失败，解析报错。

**原因：** YAML 缩进不对，或者用了 Tab。

**解决：**

- YAML 必须用空格缩进，不能用 Tab
- 列表项用 `- ` 开头
- 嵌套结构注意缩进层级

### 8. 多个 Hook 都拦截

**现象：** 有多个 PRE_TOOL_USE 的 Hook，不知道哪个先执行。

**原因：** 规则按加载顺序执行，第一个拦截的短路返回。

**解决：**

- 规则按 YAML 中的顺序执行
- 想让某个规则优先，就写在前面
- 拦截是短路的，第一个拦截的后面不执行

------

## 架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                        生命周期事件                               │
│                                                                 │
│  SESSION_START ──┐                                              │
│  USER_PROMPT_SUBMIT ──┐                                         │
│  PRE_LLM_CALL ──┐     │                                         │
│  POST_LLM_CALL ─┤     │                                         │
│  PRE_TOOL_USE ──┤     │                                         │
│  POST_TOOL_USE ─┼─────┼──────► HookEngine.fire(event, ctx)      │
│  TOOL_ERROR ────┤     │                                         │
│  ROUND_START ───┤     │                                         │
│  ROUND_END ─────┤     │                                         │
│  COMPRESSION ───┤     │                                         │
│  SESSION_END ───┘     │                                         │
│                       │                                         │
└───────────────────────┼─────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────────┐
│                          HookEngine                              │
│                                                                 │
│  rules: List<HookRule>                                          │
│                                                                 │
│  fire(event, ctx):                                              │
│      for rule in rules:                                         │
│          if rule.event != event → skip                          │
│          if !condition.evaluate(ctx) → skip                     │
│          if async → 后台执行，不等待                             │
│          else → result = action.execute(ctx)                    │
│                 if intercept → return（短路）                   │
│                 if promptInject → return                        │
│                                                                 │
└───────────────────────┬─────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────────┐
│                          HookRule                                │
│                                                                 │
│  name, description, event, condition, action, async             │
│                                                                 │
│  condition:                                                     │
│    AllOf(List)  → AND                                           │
│    AnyOf(List)  → OR                                            │
│    Matcher(field, mode, value)                                  │
│      mode: EXACT / NOT / REGEX(find) / GLOB                     │
│                                                                 │
│  action:                                                        │
│    CommandAction  → 执行 shell 命令，exit 2 = 拦截              │
│    PromptAction   → 注入提示词                                  │
│    HttpAction     → 发 HTTP 请求                                │
│    SubagentAction → 子 Agent（占位）                            │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────────┐
│                        .rook/hooks.yaml                          │
│                                                                 │
│  hooks:                                                          │
│    - name: block-rm                                             │
│      event: PRE_TOOL_USE                                        │
│      if: { all_of: [...] }                                      │
│      action: { type: command, command: "..." }                  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

------

## Hook vs 权限引擎 vs 工具代码

|   维度   | 工具代码硬编码 |       权限引擎        |       Hook 引擎       |
| :------: | :------------: | :-------------------: | :-------------------: |
| 定义方式 |   Java 代码    | Java 代码 + YAML 规则 |        纯 YAML        |
|  灵活性  |  低（改代码）  |     中（改 YAML）     |     高（纯 YAML）     |
| 适用场景 | 工具本身的逻辑 |      系统级安全       | 项目 / 用户自定义策略 |
| 执行位置 |    工具内部    |  工具执行前（五层）   |    生命周期各节点     |
| 可扩展性 |       差       |          中           |          好           |
| 学习成本 |  高（写代码）  |          中           |     低（写 YAML）     |

**三者互补：**

- 工具代码：工具本身的业务逻辑
- 权限引擎：系统级安全网（黑名单、沙箱、模式）
- Hook 引擎：用户 / 项目自定义的自动化策略

------

## 下一章

[22 - 联网搜索.md](./22-联网搜索.md) — 智谱 GLM API + HTML 备胎双通道搜索
