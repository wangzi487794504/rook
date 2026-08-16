# 09 — ReAct 多轮 Agent Loop

## 这一章做什么

实现 **AgentLoop**——AI 在一次用户请求中自动多轮 "思考 → 调工具 → 看结果 → 再思考"，直到任务完成。

**完成后你能做到：**

- 用户说 "帮我把这个 bug 修了"，AI 自己读代码、找问题、改代码、跑测试，一气呵成
- 不用用户每一步都催 "继续"
- AI 自主规划，多轮迭代，直到搞定

## 为什么需要

### 没有 AgentLoop 的痛点

之前的工具系统只能 "一次工具调用即停"：

```
用户：帮我看看项目有多少 Java 文件
  ↓
AI：[调用 glob 工具]
  ↓
工具返回：45 个文件
  ↓
程序停了...
  ↓
用户：继续，告诉我结果
  ↓
AI：一共有 45 个...
```

**用户得手动催 "继续"，体验很差。**

### 有了 AgentLoop 之后

```
用户：帮我把这个测试失败的 bug 修了
  ↓
AI 自主循环：
  Round 1: 读测试文件，看哪个失败了
  Round 2: 读源码，找问题
  Round 3: 改代码
  Round 4: 跑测试，看看过了没
  Round 5: 还有一个失败，再读，再改
  Round 6: 再跑测试，全过了
  ↓
AI：搞定了，所有测试都通过了
```

**AI 自己循环，自己判断什么时候停，用户等着就行。**

这就是 **ReAct 模式**：Reasoning（推理） + Acting（行动）。

## 核心概念

### ReAct 循环

```
while (还没完成) {
    1. 调 LLM → 它决定是说话还是调用工具
    2. 如果调用工具 → 执行工具 → 把结果喂回去
    3. 如果直接回答 → 完成，退出循环
}
```

### 停止条件

什么时候循环结束？

- **模型完成** — LLM 不再调用工具，直接给出最终回答
- **超上限** — 迭代次数超过最大值（防止死循环）
- **用户取消** — 用户说 "停"
- **连续错误** — 连续好几次工具调用都失败，可能陷入了死胡同
- **未知工具** — LLM 调用了不存在的工具

### 事件管道（BlockingQueue）

Agent 在后台线程跑，UI 在前台显示，怎么通信？

用 **事件队列**：

- Agent 产生事件（文本、工具调用、进度、错误）
- 事件放到队列里
- UI 从队列里取事件，实时显示

**解耦：Agent 不知道 UI 是什么，UI 也不知道 Agent 怎么跑的。**

### StreamCollector

流式输出的收集器，做两件事：

1. **实时推送** — 来一个 token 就推一个 token（打字效果）
2. **累积完整响应** — 同时把所有 token 攒起来，最后拿到完整文本和工具调用

**双路输出：一边实时显示，一边收集完整结果。**

------

## 第一步：LoopConfig

控制循环的各项参数。

```java
// core/agent/LoopConfig.java
package com.rook.core.agent;

public record LoopConfig(
    int maxIterations,        // 最大迭代次数，默认 25
    int maxParallelReads,     // 最大并发读工具数，默认 5
    int consecutiveErrorLimit // 连续错误容忍次数，默认 3
) {
    public LoopConfig() {
        this(25, 5, 3);  // 默认值
    }
}
```

### 三个参数详解

#### 1. maxIterations — 最大迭代次数

**为什么需要？**

防止 LLM 陷入死循环，比如：

- 反复调用同一个工具
- 来回改同一个文件
- 永远停不下来

**25 次够吗？**

- 大部分任务 3-10 轮就搞定了
- 25 是个安全上限，真有复杂任务可以调大
- 没有上限的话，可能无限烧钱

#### 2. maxParallelReads — 最大并发读工具数

传给 ToolExecutor 的参数，控制只读工具最多同时跑几个。

#### 3. consecutiveErrorLimit — 连续错误容忍次数

**什么是连续错误？**

连续好几轮工具调用都失败了。

**为什么要限制？**

- 可能 LLM 陷入了死胡同，越错越乱
- 继续下去也是浪费时间和钱
- 连续错 3 次就停下来，告诉用户搞不定

------

## 第二步：AgentEvent — 事件系统

Agent 和 UI 之间通信用的事件。

```java
// core/agent/AgentEvent.java
package com.rook.core.agent;

import com.rook.tools.ToolCall;
import com.rook.tools.ToolResult;

public record AgentEvent(
    AgentEventType type,      // 事件类型
    String text,              // 文本内容（TEXT 类型用）
    ToolCall toolCall,        // 工具调用（TOOL_CALL 类型用）
    ToolResult toolResult,    // 工具结果（TOOL_RESULT 类型用）
    int iteration,            // 当前迭代次数
    int totalIterations       // 总迭代次数（进度用）
) {
    // 快捷构造方法
    public static AgentEvent text(String t) {
        return new AgentEvent(AgentEventType.TEXT, t, null, null, 0, 0);
    }
    
    public static AgentEvent toolCall(ToolCall tc) {
        return new AgentEvent(AgentEventType.TOOL_CALL, null, tc, null, 0, 0);
    }
    
    public static AgentEvent toolResult(ToolResult tr) {
        return new AgentEvent(AgentEventType.TOOL_RESULT, null, null, tr, 0, 0);
    }
    
    public static AgentEvent progress(int iter, int total) {
        return new AgentEvent(AgentEventType.PROGRESS, null, null, null, iter, total);
    }
    
    public static AgentEvent done() {
        return new AgentEvent(AgentEventType.DONE, null, null, null, 0, 0);
    }
    
    public static AgentEvent error(String msg) {
        return new AgentEvent(AgentEventType.ERROR, msg, null, null, 0, 0);
    }
}

enum AgentEventType {
    TEXT,        // 文本 token（流式输出）
    TOOL_CALL,   // 工具调用开始
    TOOL_RESULT, // 工具执行结果
    TOKEN_USAGE, // token 用量统计
    PROGRESS,    // 进度更新
    ERROR,       // 错误
    DONE         // 全部完成
}
```

### 为什么用事件模式？

#### 解耦

Agent 只负责产生事件，不管谁来消费。

- 可以是终端 UI
- 可以是 Web UI
- 可以是日志
- 可以同时有多个消费者

#### 异步

Agent 在后台线程跑，UI 不阻塞。

- Agent 慢慢跑
- UI 实时更新
- 用户可以随时取消

### 事件类型详解

|     类型      |         含义         |          携带数据          |
| :-----------: | :------------------: | :------------------------: |
|    `TEXT`     | LLM 输出的文本 token |            text            |
|  `TOOL_CALL`  |   LLM 决定调用工具   |          toolCall          |
| `TOOL_RESULT` |     工具执行完了     |         toolResult         |
|  `PROGRESS`   |       进度更新       | iteration, totalIterations |
| `TOKEN_USAGE` |      token 用量      |      text（统计信息）      |
|    `ERROR`    |        出错了        |      text（错误信息）      |
|    `DONE`     |       全部完成       |             无             |

### 快捷构造方法

为什么要有 `text()`、`toolCall()` 这些静态方法？

**对比：**

```java
// 不用快捷方法，每次都要写这么多
eventQueue.put(new AgentEvent(Agen 
```

代码更可读，也不容易写错字段。

------

## 第三步：AgentLoop — 核心循环

最核心的部分，整个 Agent 的大脑。

```java
// core/agent/AgentLoop.java
public class AgentLoop {

    private final LoopConfig config;
    private volatile LoopState state = LoopState.RUNNING;
    private int consecutiveErrors = 0;

    /**
     * 启动 Agent 循环，返回事件队列
     * 调用方从队列里消费事件，实时显示
     */
    public BlockingQueue<AgentEvent> run(
            List<ChatMessage> history, 
            LlmProvider provider, 
            ToolRegistry tools) {

        // 1. 创建事件队列
        BlockingQueue<AgentEvent> eventQueue = new LinkedBlockingQueue<>(1000);
        state = LoopState.RUNNING;

        // 2. 启动后台线程
        new Thread(() -> {
            try {
                int iteration = 1;

                // ★ 主循环
                while (state == LoopState.RUNNING) {
                    
                    // ── 第 1 步：检查停止条件 ──
                    if (iteration > config.maxIterations()) {
                        eventQueue.put(AgentEvent.error("达到最大迭代次数 " + config.maxIterations()));
                        break;
                    }

                    // 发进度事件
                    eventQueue.put(AgentEvent.progress(iteration, config.maxIterations()));

                    // ── 第 2 步：准备收集器 ──
                    StreamCollector collector = new StreamCollector(
                        token -> { 
                            try { 
                                eventQueue.put(AgentEvent.text(token)); 
                            } catch (InterruptedException e) {} 
                        });

                    // ── 第 3 步：调用 LLM ──
                    provider.stream(history, tools, collector);

                    // ── 第 4 步：解析结果 ──
                    String text = collector.getFullText();
                    List<ToolCall> toolCalls = collector.getToolCalls();

                    // ── 第 5 步：没有工具调用 → 完成 ──
                    if (toolCalls.isEmpty()) {
                        history.add(new ChatMessage("assistant", text));
                        state = L oopState.COMPLETED;
                        break;
                    }

                    // ── 第 6 步：通知工具调用 ──
                    for (ToolCall tc : toolCalls) {
                        eventQueue.put(AgentEvent.toolCall(tc));
                    }

                    // ── 第 7 步：执行工具 ──
                    ToolExecutor executor = new ToolExecutor(tools,
                        config.maxParallelReads(), permissionEngine);
                    List<ToolResult> results = executor.execute(toolCalls);

                    // ── 第 8 步：回灌结果 ──
                    for (int i = 0; i < toolCalls.size(); i++) {
                        ToolCall tc = toolCalls.get(i);
                        ToolResult tr = results.get(i);
                        
                        eventQueue.put(AgentEvent.toolResult(tr));
                        
                        // 把工具结果加入历史，下一轮发给 LLM
                        history.add(ChatMessage.toolResult(
                            tc.id(), 
                            tc.name(),
                            tr.success() ? tr.content() : "ERROR: " + tr.content()
                        ));
                    }

                    // ── 第 9 步：错误计数 ──
                    if (results.stream().allMatch(ToolResult::success)) {
                        consecutiveErrors = 0;  // 全成功，清零
                    } else {
                        consecutiveErrors++;    // 有失败，加 1
                        if (consecutiveErrors >= config.consecutiveErrorLimit()) {
                            eventQueue.put(AgentEvent.error("连续错误 " + consecutiveErrors + " 次，停止"));
                            break;
                        }
                    }

                    iteration++;
                }
            } catch (Exception e) {
                try {
                    eventQueue.put(AgentEvent.error("循环异常: " + e.getMessage()));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                // 不管正常还是异常，最后发一个 DONE
                try {
                    eventQueue.put(AgentEvent.done());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "agent-loop").start();

        return eventQueue;  // 返回事件队列给调用方
    }

    /** 取消执行 */
    public void cancel() {
        state = LoopState.CANCELLED;
    }
}

enum LoopState {
    RUNNING,    // 运行中
    COMPLETED,  // 正常完成
    CANCELLED   // 被取消
}
```

------

## 逐段详解

### 1. 状态字段

```
private volatile LoopState state = LoopState.RUNNING;
private int consecutiveErrors = 0;
```

**`state` — 循环状态**

- RUNNING：运行中
- COMPLETED：正常完成
- CANCELLED：被取消

**为什么用 `volatile`？**

- state 可能被多个线程读写
- 一个线程改（cancel 方法），另一个线程读（循环里判断）
- volatile 保证可见性，改了立刻能看到

**`consecutiveErrors` — 连续错误计数**

- 连续错几次就停
- 成功一次就清零

### 2. run () 方法 — 启动循环

```java
public BlockingQueue<AgentEvent> run(...) {
    BlockingQueue<AgentEvent> eventQueue = new LinkedBlockingQueue<>(1000);
    state = LoopState.RUNNING;
    
    new Thread(() -> {
        // 循环逻辑...
    }, "agent-loop").start();
    
    return eventQueue;
}
```

**设计要点：**

- 立即返回，不阻塞调用方
- 返回事件队列，调用方从队列里取事件
- 实际工作在后台线程做

**为什么用 LinkedBlockingQueue？**

- 线程安全，多线程读写没问题
- 有容量限制（1000），防止内存爆掉
- 阻塞式取，UI 线程可以等着新事件

### 3. 主循环的 9 个步骤

每一轮迭代做 9 件事：

```
┌─────────────────────────────────────┐
│         第 N 轮迭代                  │
│                                     │
│  1. 检查停止条件（超上限了吗？）       │
│  2. 发进度事件                       │
│  3. 准备 StreamCollector             │
│  4. 调用 LLM（流式）                 │
│  5. 拿到完整文本和工具调用列表         │
│  6. 没有工具调用？→ 完成，退出        │
│  7. 有工具调用？→ 执行工具            │
│  8. 工具结果回灌到历史                │
│  9. 更新错误计数                     │
│                                     │
│  → 下一轮迭代                        │
└─────────────────────────────────────┘
```

#### 第 1 步：检查停止条件

```
if (iteration > config.maxIterations()) {
    eventQueue.put(AgentEvent.error("达到最大迭代次数"));
    break;
}
```

**防止死循环的安全网。**

#### 第 2 步：发进度事件

```
eventQueue.put(AgentEvent.progress(iteration, config.maxIterations()));
```

告诉 UI：现在是第 X 轮，总共最多 Y 轮。

UI 可以显示进度条或者 "第 3/25 轮"。

#### 第 3 步：准备 StreamCollector

```java
StreamCollector collector = new StreamCollector(
    token -> { 
        try { 
            eventQueue.put(AgentEvent.text(token)); 
        } catch (InterruptedException e) {} 
    });
```

**收集器做两件事：**

1. 来一个 token，就往事件队列里发一个 → UI 实时显示打字效果
2. 同时把所有 token 攒起来 → 最后拿到完整文本和工具调用

**这就是 "双路输出"：实时 + 累积。**

#### 第 4 步：调用 LLM

```
provider.stream(history, tools, collector);
```

调用 LLM 的流式接口，传入：

- `history` — 对话历史
- `tools` — 可用工具定义
- `collector` — 回调，流式输出的 token 都给它

**这一步是阻塞的**，等 LLM 全部输出完才往下走。

#### 第 5 步：解析结果

```
String text = collector.getFullText();
List<ToolCall> toolCalls = collector.getToolCalls();
```

从收集器里拿到：

- 完整的文本内容
- 工具调用列表（可能有多个）

#### 第 6 步：没有工具调用 → 完成

```java
if (toolCalls.isEmpty()) {
    history.add(new ChatMessage("assistant", text));
    state = LoopState.COMPLETED;
    break;
}
```

**如果 LLM 没有调用工具，说明它觉得任务完成了，直接给出了最终回答。**

- 把回答加入历史
- 设置状态为 COMPLETED
- 退出循环

**这是正常的结束方式。**

#### 第 7 步：执行工具

```java
ToolExecutor executor = new ToolExecutor(tools,
    config.maxParallelReads(), permissionEngine);
List<ToolResult> results = executor.execute(toolCalls);
```

用 ToolExecutor 执行所有工具调用。

- 只读工具并发跑
- 写入工具串行跑
- 自动权限检查

#### 第 8 步：回灌结果

```java
for (int i = 0; i < toolCalls.size(); i++) {
    ToolCall tc = toolCalls.get(i);
    ToolResult tr = results.get(i);
    
    eventQueue.put(AgentEvent.toolResult(tr));
    
    history.add(ChatMessage.toolResult(
        tc.id(), 
        tc.name(),
        tr.success() ? tr.content() : "ERROR: " + tr.content()
    ));
}
```

**两件事：**

1. 发事件通知 UI：工具执行完了，结果是 xxx
2. 把工具结果加入对话历史，下一轮发给 LLM

**"回灌" 是什么意思？**

把工具执行的结果，作为一条新的消息，加回对话历史里。这样下一轮调 LLM 的时候，它就能看到工具的结果，基于结果继续思考。

```
历史消息：
  user: 帮我看看有多少 Java 文件
  assistant: [调用 glob 工具]   ← 上一轮 LLM 的输出
  user: [工具结果: 45 个文件]   ← 这就是回灌的结果
  ↓
下一轮 LLM 看到这些，就知道工具返回了什么，继续下一步
```

#### 第 9 步：错误计数

```java
if (results.stream().allMatch(ToolResult::success)) {
    consecutiveErrors = 0;  // 全成功，清零
} else {
    consecutiveErrors++;    // 有失败，加 1
    if (consecutiveErrors >= config.consecutiveErrorLimit()) {
        eventQueue.put(AgentEvent.error("连续错误，停止"));
        break;
    }
}
```

**连续错误保护。**

- 这一轮所有工具都成功 → 计数器清零
- 有失败的 → 计数器加 1
- 连续错 N 次 → 停下来，不继续了

**为什么需要这个？**

LLM 有时候会陷入死胡同：

- 调用工具 A 失败 → 再试 → 又失败 → 再试...
- 越错越乱，越乱越错
- 不如早点停下来，告诉用户搞不定

### 4. finally — 发 DONE 事件

```
finally {
    eventQueue.put(AgentEvent.done());
}
```

**不管正常完成、异常、还是取消，最后一定发一个 DONE 事件。**

UI 收到 DONE 就知道：结束了，可以停止等待了。

**这是一种约定：**

- 事件流的最后一个事件一定是 DONE
- UI 可以根据这个来判断什么时候结束

### 5. cancel () — 取消

```
public void cancel() {
    state = LoopState.CANCELLED;
}
```

用户想停下来的时候，调用这个方法。

**怎么生效的？**

- 主循环每轮开头检查 `state == LoopState.RUNNING`
- cancel 把 state 改成 CANCELLED
- 下一轮循环条件不满足，就退出了

**注意：这是 "协作式取消"**

- 不是强制杀死线程
- 等当前这一轮跑完才退出
- 好处：安全，不会留一半状态
- 坏处：不是立刻停，当前 LLM 调用或工具执行会跑完

------

## 第四步：StreamCollector 详解

流式收集器，一边实时推送，一边累积完整结果。

```java
// 伪代码，帮助理解
class StreamCollector implements StreamCallback {
    private final Consumer<String> tokenConsumer;  // 实时推送
    private final StringBuilder fullText = new StringBuilder();  // 累积文本
    private final List<ToolCall> toolCalls = new ArrayList<>();  // 累积工具调用
    
    // 来一个 token
    @Override
    public void onToken(String token) {
        fullText.append(token);           // 攒起来
        tokenConsumer.accept(token);      // 同时推出去
    }
    
    // 来一个工具调用（流式过程中逐步构建）
    @Override
    public void onToolCall(ToolCall tc) {
        toolCalls.add(tc);
    }
    
    // 拿到完整文本
    public String getFullText() {
        return fullText.toString();
    }
    
    // 拿到所有工具调用
    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }
}
```

**核心思想：一个输入，两个输出。**

```
流式 token 输入
    │
    ├─→ 实时推送 → UI 显示打字效果
    │
    └─→ 累积收集 → 最后拿到完整结果
```

**为什么需要这个？**

- UI 需要实时显示，不能等全部完了才显示
- 但逻辑上又需要完整的结果来判断下一步
- 所以两边都要

------

## 完整流程走查

### 示例：统计 Java 文件数量并读一个

**用户输入：** "帮我看看项目有多少 Java 文件，然后读其中一个"

------

**Round 1（第 1 轮）：**

```
1. 检查停止条件：1 <= 25，继续
2. 发进度：第 1/25 轮
3. 调 LLM，传入历史 + 工具定义
4. LLM 流式输出：
   "好的，让我先搜索一下项目里的 Java 文件..."
   [调用工具: glob(pattern="**/*.java")]
5. 收集到：
   text = "好的，让我先搜索一下..."
   toolCalls = [glob("**/*.java")]
6. 有工具调用，继续
7. 执行工具：glob → 返回 37 个文件
8. 回灌结果：把 "37 个文件" 加入历史
9. 错误计数：成功，清零
10. iteration = 2，下一轮
```

------

**Round 2（第 2 轮）：**

```
1. 检查停止条件：2 <= 25，继续
2. 发进度：第 2/25 轮
3. 调 LLM，传入更新后的历史
   （历史里现在有：用户问题 + AI回答 + 工具结果）
4. LLM 流式输出：
   "找到了 37 个 Java 文件。让我读一下 Main.java 看看入口..."
   [调用工具: read_file(filePath="src/main/java/Main.java")]
5. 收集到：
   text = "找到了 37 个..."
   toolCalls = [read_file("src/main/java/Main.java")]
6. 有工具调用，继续
7. 执行工具：read_file → 返回文件内容
8. 回灌结果：把文件内容加入历史
9. 错误计数：成功，清零
10. iteration = 3，下一轮
```

------

**Round 3（第 3 轮）：**

```
1. 检查停止条件：3 <= 25，继续
2. 发进度：第 3/25 轮
3. 调 LLM，传入更新后的历史
4. LLM 流式输出：
   "项目一共有 37 个 Java 文件。Main.java 的内容是...
    这是程序的入口，主要做了..."
5. 收集到：
   text = "项目一共有 37 个 Java 文件..."
   toolCalls = []  ← 没有工具调用！
6. 没有工具调用 → 完成！
7. 把最终回答加入历史
8. state = COMPLETED
9. break，退出循环
```

------

**最终：**

- 发 DONE 事件
- UI 显示最终回答
- 用户看到结果

------

## 运行验证

### 终端里的效果

```
> 帮我看看项目有多少 Java 文件，然后读其中一个

[第 1/25 轮]
[调用工具: glob(pattern="**/*.java")]
[工具结果: 找到 37 个文件]

[第 2/25 轮]
[调用工具: read_file(filePath="src/main/java/Main.java")]
[工具结果: package com.rook... ]

[第 3/25 轮]
项目一共有 37 个 Java 文件，主要分布在...
Main.java 是程序入口，内容是...

✓ 完成
```

### 验证点

-  多轮循环正常工作
-  工具结果正确回灌
-  没有工具调用时正确停止
-  达到最大迭代次数时停止
-  连续错误时停止
-  cancel () 能取消
-  事件顺序正确（TEXT → TOOL_CALL → TOOL_RESULT → DONE）

------

## 常见坑（详细版）

### 1. tool_result 消息格式

**现象：** LLM 看不到工具结果，或者报错说格式不对。

**原因：** Anthropic 协议对 tool_result 的消息格式有严格要求。

**正确格式（Anthropic）：**

```
所有 tool_result 必须放在同一条 user 消息的 content 数组里：

{
  "role": "user",
  "content": [
    {"type": "tool_result", "tool_use_id": "id1", "content": "结果1"},
    {"type": "tool_result", "tool_use_id": "id2", "content": "结果2"}
  ]
}
```

**错误格式：**

```
❌ 拆成多条 user 消息：
  user: [tool_result 1]
  user: [tool_result 2]
  
❌ 放在 assistant 消息里
```

**为什么？**

Anthropic 的协议规定，一轮对话的结构是：

- user 消息
- assistant 消息（可能包含 tool_use）
- user 消息（包含 tool_result，可能多个）
- assistant 消息（可能包含 tool_use）
- ...

**每一轮：user → assistant → user → assistant**

### 2. Assistant + tool_use 消息也要存

**现象：** 多轮之后 LLM 忘了之前调用过什么工具。

**原因：** 只存了 tool_result，没存 assistant 的 tool_use。

**正确的历史消息顺序：**

```
1. user: 用户问题
2. assistant: 文本 + tool_use（调用工具）  ← 这条也要存！
3. user: tool_result（工具结果）           ← 回灌的结果
4. assistant: 文本 + tool_use（又调用工具） ← 这条也要存！
5. user: tool_result（工具结果）
6. assistant: 最终回答
```

**为什么两条都要存？**

LLM 需要看到完整的对话轨迹：

- 我之前说了什么
- 我调用了什么工具
- 工具返回了什么
- 我又说了什么

缺了任何一环，上下文就断了。

### 3. 循环死锁 / 无限循环

**现象：** AI 不停地调用工具，永远停不下来。

**可能的原因：**

- 没有 maxIterations 上限
- LLM 陷入了循环（反复调用同一个工具）
- 工具结果格式不对，LLM 看不懂，一直重试

**解决：**

- 一定要有 maxIterations 上限（安全网）
- 连续错误限制
- 监控 token 用量，异常时停止

### 4. 事件队列满了

**现象：** 事件太多，队列满了，put 阻塞住了。

**原因：**

- UI 消费太慢
- 事件产生太快
- 队列容量不够

**解决：**

- 队列容量设大一点（比如 1000）
- UI 消费要快，不要在消费线程里做重活
- 或者用 offer + 丢弃策略，保证不阻塞

### 5. 取消不及时

**现象：** 点了取消，还要等好久才停。

**原因：**

- 协作式取消，要等当前这一轮跑完
- 如果当前正在调 LLM，可能要等 LLM 返回

**优化方向：**

- LLM 流式调用支持中断
- 工具执行支持超时和取消
- 但这会增加复杂度，初期可以接受 "不是立刻停"

### 6. 历史消息越来越长

**现象：** 多轮之后，历史消息越来越长，token 用量爆炸，速度变慢。

**原因：**

- 每一轮都加几条消息
- 工具结果可能很长（比如读一个大文件）

**解决：**

- 限制最大历史消息数
- 截断过长的工具结果
- 或者用摘要机制，把老的历史压缩成摘要

------

## 00-09 总结：你现在有一个完整的 AI 编程助手了

```
00 环境搭建
  │
  ▼
01 整体架构
  │
  ▼
02 LLM API 调用（Anthropic 协议）
  │
  ├─→ 03 OpenAI 协议适配 + Provider 工厂
  │
  ├─→ 04 JLine3 终端交互界面
  │
  ├─→ 05 配置加载 + 会话持久化
  │
  └─→ 06 Tool 接口 + ToolRegistry
          │
          ▼
        07 六个核心工具
          │
          ▼
        08 工具执行器（并发/串行/权限）
          │
          ▼
        09 ReAct 多轮 Agent Loop
```

**现在你拥有的能力：**

- 支持多种大模型（Anthropic / OpenAI 协议）
- 漂亮的终端交互界面
- 配置文件和会话持久化
- 六个核心工具（读 / 写 / 改文件、执行命令、文件搜索、内容搜索）
- 智能的工具执行器（并发 / 串行 / 权限）
- 自主多轮循环的 Agent

**AI 能做的事：**

- 读文件、写代码、改代码
- 跑命令、跑测试
- 搜索代码、搜索文件
- 自主规划，多轮迭代完成任务

**这就是一个完整的 AI 编程助手了。**

后面的 10-28 章，都是在这个基础上叠加更多能力：

- MCP 远程工具
- Skill 系统
- 记忆系统
- 多模态
- 等等...
