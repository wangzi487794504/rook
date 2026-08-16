# 02 — LLM API 调用（Anthropic 协议）

## 这一章做什么

用 Java 11 自带的 `java.net.http.HttpClient` 调用 DeepSeek V4 的 Anthropic 兼容接口，实现两个核心能力：

- **流式接收**（stream）：像打字机一样逐字输出，用户体验好
- **完整返回**（complete）：一次性拿到全部回复，适合后台任务

这是 Rook 和 AI 模型之间的桥梁。没有这一层，后面的所有 AI 能力都是空的。

## 为什么需要

你可能会问：直接用 OkHttp 或者 RestTemplate 不行吗？为什么选 Java 11 自带的 HttpClient？

**三个原因：**

1. **零依赖**：不用引入任何第三方 HTTP 库，减少 jar 包体积和冲突
2. **原生支持 SSE**：InputStream 逐行读取就是 SSE 的天然解析方式
3. **现代 API**：链式调用、异步支持、连接池管理，比老的 HttpURLConnection 好用太多

## 核心概念

### HttpClient

Java 11 内置的 HTTP 客户端，替代了古老的 `HttpURLConnection`。支持 HTTP/2、异步、连接池、WebSocket。

**为什么用它不用别的？**

- 零依赖，开箱即用
- 性能足够（连接池复用、异步非阻塞）
- API 设计现代，代码写起来干净

### SSE (Server-Sent Events)

服务器向客户端单向推送事件流的协议。本质上就是一个特殊的 HTTP 响应：

- `Content-Type: text/event-stream`
- 响应体是一行行的文本，每行以 `data: ` 开头
- 事件之间用空行分隔

**长这样：**

```plaintext
data: {"type":"message_start", "message": {...}}

data: {"type":"content_block_delta", "delta": {"type":"text_delta", "text":"你"}}

data: {"type":"content_block_delta", "delta": {"type":"text_delta", "text":"好"}}

data: {"type":"message_stop"}
```

**为什么需要流式？**

- 用户体验：不用等全部生成完才看到第一个字
- 减少等待：大模型生成 1000 字可能要 5 秒，流式可以边生成边看
- 内存友好：不用把整个响应存在内存里

### Anthropic Messages API

Anthropic 公司定义的对话 API 格式，现在很多国产模型（DeepSeek、智谱等）都兼容这个协议。

**请求格式：**

```json
{
  "model": "deepseek-v4-pro",
  "max_tokens": 4096,
  "stream": true,
  "messages": [
    {"role": "user", "content": "你好"}
  ]
}
```

**和 OpenAI 协议的区别：**

|    维度    |       Anthropic       |         OpenAI          |
| :--------: | :-------------------: | :---------------------: |
|   认证头   |      `x-api-key`      | `Authorization: Bearer` |
|   版本头   |  `anthropic-version`  |         不需要          |
|  流式事件  | `content_block_delta` | `chat.completion.chunk` |
| 最大 token |     `max_tokens`      | `max_completion_tokens` |

### ProviderConfig

把 base_url、api_key、model 这些配置封装成一个 record，方便切换不同的模型供应商。

**为什么需要抽象？**

- 今天用 DeepSeek，明天可能换 Claude
- 开发环境用便宜的模型，生产环境用好的模型
- 配置和代码分离，api_key 不会硬编码

------

## 第一步：ProviderConfig

```java
// config/ProviderConfig.java
package com.rook.config;

/**
 * 单个 LLM Provider 的配置记录。
 * @param name    Provider 名称，如 "deepseek"，用于日志和用户展示
 * @param protocol 协议类型：anthropic | openai，决定用哪个 Provider 实现
 * @param model   模型名，如 "deepseek-v4-pro"，传给 API 的 model 字段
 * @param baseUrl API 地址，如 https://api.deepseek.com/anthropic
 * @param apiKey  认证密钥，支持 ${DEEPSEEK_API_KEY} 环境变量占位符
 */
public record ProviderConfig(
    String name,
    String protocol,
    String model,
    String baseUrl,
    String apiKey
) {}
```

**为什么用 record 不用 class？**

- 这就是个纯数据载体，没有业务逻辑
- record 自动生成构造器、getter、equals、hashCode、toString
- 不可变，线程安全，适合做配置对象

**五个字段的作用：**

1. `name` — 给人看的名字，日志里打印 "正在使用 deepseek 模型"
2. `protocol` — 决定用哪个实现类，`anthropic` 走 AnthropicProvider，`openai` 走 OpenAIProvider
3. `model` — 具体模型名，直接传给 API
4. `baseUrl` — API 根地址，不同供应商不一样
5. `apiKey` — 密钥，认证用的，注意不要硬编码到代码里

------

## 第二步：ChatMessage

```java
// llm/ChatMessage.java
package com.rook.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatMessage(
    @JsonProperty("role") String role,
    @JsonProperty("content") String content
) {}
```

**这是什么？**

一条聊天消息，包含两个字段：

- ```
  role
  ```

  ：消息的角色，有三种：

  - `user` — 用户说的话
  - `assistant` — AI 说的话
  - `system` — 系统提示词（人设、规则）

- `content`：消息内容，就是文本

**为什么需要这个类？**

大模型 API 接收的是一个消息数组（对话历史），不是单条消息。你需要把多轮对话都传过去，模型才能理解上下文。

**注解解释：**

- `@JsonIgnoreProperties(ignoreUnknown = true)` — 反序列化时忽略未知字段。API 返回的 JSON 可能有多余字段，加这个不会报错
- `@JsonProperty("role")` — 显式指定 JSON 字段名，防止编译时字段名被混淆

**为什么用 record？**

- 消息对象创建后就不应该改
- 不可变对象更安全，不用担心被意外修改

------

## 第三步：LlmProvider 接口

```java
// llm/LlmProvider.java
package com.rook.llm;

import java.util.List;

public interface LlmProvider {
    String name();
    void stream(List<ChatMessage> messages, StreamCallback callback) throws LlmException;
    String complete(List<ChatMessage> messages) throws LlmException;
}
```

**为什么要定义接口？**

这是面向接口编程的经典用法。好处：

1. **可替换**：今天用 Anthropic 协议，明天换 OpenAI 协议，只需要换实现类，调用方代码不用改
2. **可测试**：单元测试时可以写一个 MockProvider，不用真的调 API
3. **可扩展**：以后加新的供应商（比如智谱、通义），只要新写一个实现类

**三个方法：**

1. `name()` — 返回 provider 的名字，用于日志和展示
2. `stream(...)` — 流式调用，逐字回调，适合交互式场景
3. `complete(...)` — 一次性返回完整结果，适合后台任务

**为什么两个方法都要？**

- 流式（stream）：用户在终端里聊天，要看到打字效果
- 完整（complete）：后台批量处理、生成报告、不需要实时展示的场景

------

## 第四步：StreamCallback

```java
// llm/StreamCallback.java
package com.rook.llm;

@FunctionalInterface
public interface StreamCallback {
    void onToken(String token);
}
```

**这是什么？**

一个函数式接口，用来接收流式输出的每一个 token（可以理解为 "一个字" 或 "一个词片段"）。

**为什么用回调不用返回值？**

流式输出是持续的、异步的，不是一次性返回的。回调模式最适合：

- 每收到一个 token，就调用一次 `onToken`
- 调用方拿到 token 后可以立刻打印到终端
- 不用等全部生成完

**函数式接口的好处：**

可以直接用 Lambda 表达式：

```java
provider.stream(messages, token -> System.out.print(token));
```

**token 是什么？**

大模型不是一个字一个字生成的，而是一个 token 一个 token 生成的。

- 英文里一个 token 大概是 3-4 个字母，或者一个单词
- 中文里一个 token 大概是 1-2 个字
- 对用户来说，理解成 "一小段文字" 就行，不用纠结具体是什么

------

## 第五步：LlmException

```java
// llm/LlmException.java
package com.rook.llm;

public class LlmException extends Exception {
    private final int statusCode;
    public LlmException(String msg, int code) { super(msg); this.statusCode = code; }
    public LlmException(String msg, Throwable cause) { super(msg, cause); this.statusCode = -1; }
    public int getStatusCode() { return statusCode; }
}
```

**为什么自定义异常？**

把各种错误统一封装，调用方可以统一处理：

- HTTP 状态码错误（401 未授权、429 限流、500 服务器错误）
- 网络错误（连接超时、DNS 解析失败）
- 解析错误（JSON 格式不对）

**两个构造器：**

1. `LlmException(msg, code)` — API 返回了错误状态码，比如 401、429
2. `LlmException(msg, cause)` — 底层异常，比如 IOException、InterruptedException

**statusCode = -1 是什么意思？**

表示这个错误不是 HTTP 状态码错误，而是网络异常或其他内部错误，没有状态码。用 -1 作为占位符。

------

## 第六步：AnthropicProvider（完整代码）

这是核心实现类，我们逐段拆解。

### 类结构和构造器

```java
public class AnthropicProvider implements LlmProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ProviderConfig config;
    private final HttpClient httpClient;

    public AnthropicProvider(ProviderConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newHttpClient();
    }
```

**三个字段：**

1. `MAPPER` — Jackson 的 ObjectMapper，用来序列化和反序列化 JSON。设为 static final，全局共享一个实例（ObjectMapper 是线程安全的）
2. `config` — 配置对象，保存 baseUrl、apiKey、model 等
3. `httpClient` — HTTP 客户端实例，在构造器里创建

**为什么 httpClient 是成员变量？**

HttpClient 内部有连接池，复用同一个实例可以：

- 复用 TCP 连接，减少握手开销
- 不用每次请求都创建新的客户端
- 性能更好

### name () 方法

```java
    @Override
    public String name() { return config.name(); }
```

直接返回配置里的名字，简单明了。

### stream () 方法 — 核心

这是最复杂的方法，分三步：构建请求 → 发送请求 → 解析 SSE。

#### 第一步：构建请求体

```java
            // 1. 构建请求体
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", config.model());
            body.put("max_tokens", 4096);
            body.put("stream", true);
            body.put("messages", messages.stream().map(m -> {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("role", m.role());
                mm.put("content", m.content());
                return mm;
            }).toList());

            String bodyJson = MAPPER.writeValueAsString(body);
```

**为什么用 Map 不用专门的请求类？**

- 请求体结构简单，字段不多
- 用 Map 更灵活，不用为每个 API 都写一个 DTO 类
- 快速原型开发阶段更高效

**四个字段的作用：**

1. `model` — 模型名称，告诉 API 用哪个模型
2. `max_tokens` — 最大生成 token 数，防止模型无限生成。4096 是个比较合理的值，大约相当于 2000-3000 个汉字
3. `stream` — 是否开启流式输出。`true` 就是 SSE 流式，`false` 就是一次性返回
4. `messages` — 对话历史数组，把我们的 ChatMessage 列表转换成 Map 列表

**为什么用 LinkedHashMap？**

- HashMap 不保证顺序，序列化后的 JSON 字段顺序不确定
- LinkedHashMap 保持插入顺序，调试时看 JSON 更舒服
- 对功能没有影响，只是调试友好

#### 第二步：发送 POST 请求

```java
            // 2. 发送 POST
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + "/messages"))
                .header("x-api-key", config.apiKey())
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                .build();

            HttpResponse<java.io.InputStream> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofInputStream());
```

| 版本         | 日期       | 变化                                |
| ------------ | ---------- | ----------------------------------- |
| `2023-06-01` | 2023-06-01 | Messages API 首个稳定版，**最通用** |
| `2024-02-29` | 2024-02-29 | 增加了一些新字段，核心格式没变      |
| `2025-01-24` | 2025-01-24 | 工具调用格式更新                    |

**三个请求头的作用：**

1. `x-api-key` — 认证用的，把你的 API Key 传过去。注意 Anthropic 协议用这个头，不是 `Authorization: Bearer xxx`
2. `anthropic-version` — API 版本号，Anthropic 要求必须传，用来保证兼容性
3. `content-type` — 请求体格式，告诉服务器我们发的是 JSON

**为什么用 InputStream 接收响应？**

- SSE 是流式的，数据一点一点过来
- 用 InputStream 可以边读边处理，不用等全部下载完
- 内存占用小，特别是长回复

**状态码检查：**

```java
            int status = response.statusCode();
            if (status != 200) {
                String errorBody = new String(
                    response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new LlmException("API Error (" + status + "): " + errorBody, status);
            }
```

如果不是 200，说明出错了。把错误响应体读出来，连同状态码一起抛异常，方便排查问题。

**常见错误状态码：**

- `401` — API Key 不对，或者没传认证头
- `429` — 请求太频繁，被限流了
- `400` — 请求格式不对，比如缺字段、参数错误
- `500` — 服务器内部错误

#### 第三步：逐行解析 SSE

```java
            // 3. 逐行解析 SSE
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data: ")) continue;
                    String data = line.substring(6);

                    Map<String, Object> event = MAPPER.readValue(data, Map.class);
                    String type = (String) event.get("type");

                    // content_block_delta → 获取 token
                    if ("content_block_delta".equals(type)) {
                        Map<String, Object> delta = (Map<String, Object>) event.get("delta");
                        if (delta != null && "text_delta".equals(delta.get("type"))) {
                            String text = (String) delta.get("text");
                            if (text != null) callback.onToken(text);
                        }
                    }
                    if ("message_stop".equals(type)) break;
                }
            }
```

**SSE 解析的核心逻辑：**

1. **用 BufferedReader 逐行读** — SSE 就是一行一行的文本
2. **只处理 `data: ` 开头的行** — SSE 协议里，数据行都是 `data: ` 开头的，其他行（注释、空行）跳过
3. **去掉 `data: ` 前缀** — 拿到纯 JSON 字符串
4. **解析 JSON** — 用 Jackson 解析成 Map
5. **判断事件类型** — 根据 `type` 字段做不同处理

**Anthropic 流式的事件类型：**

|       事件类型        |            含义             |      我们做什么      |
| :-------------------: | :-------------------------: | :------------------: |
|    `message_start`    | 消息开始，包含 message 对象 |         忽略         |
| `content_block_start` |         内容块开始          |         忽略         |
| `content_block_delta` | 内容增量，包含新生成的文字  | ✅ 提取文字，回调出去 |
| `content_block_stop`  |         内容块结束          |         忽略         |
|    `message_delta`    | 消息元数据更新（比如用量）  |         忽略         |
|    `message_stop`     |        整个消息结束         |      ✅ 退出循环      |

**为什么只关心 `content_block_delta` 和 `message_stop`？**

- 我们只需要文字内容，其他事件对终端用户没意义
- 简单、够用就行，以后需要更多功能再加

**为什么用 try-with-resources？**

- `BufferedReader` 实现了 `AutoCloseable` 接口
- try 块结束后自动关闭流，不会资源泄漏
- 不用手动写 finally 里 close ()

### 异常处理

```java
        } catch (LlmException e) {
            throw e; // 重新抛出业务异常
        } catch (IOException e) {
            throw new LlmException("网络错误: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("请求被中断", e);
        }
```

**三种异常的处理：**

1. **LlmException** — 我们自己抛的业务异常（比如 API 返回 401），直接重新抛出
2. **IOException** — 网络错误（连接超时、读取失败），包装成 LlmException 再抛
3. **InterruptedException** — 线程被中断（比如用户取消了请求），恢复中断标记后抛异常

**为什么要恢复中断标记？**

`Thread.currentThread().interrupt()` — 这是 Java 并发编程的最佳实践。捕获 InterruptedException 后，应该恢复中断状态，让上层代码也能感知到中断。

### complete () 方法

```java
    @Override
    public String complete(List<ChatMessage> messages) throws LlmException {
        StringBuilder sb = new StringBuilder();
        stream(messages, sb::append);
        return sb.toString();
    }
```

**巧妙的实现：**

不用重新写一套非流式的逻辑，直接复用 stream 方法：

- 创建一个 StringBuilder
- 每个 token 都 append 进去
- 最后返回完整的字符串

**好处：**

- 代码复用，不用维护两套逻辑
- 行为一致，流式和非流式的结果完全一样
- 简单、不容易出 bug

------

## 完整代码所在文件

```
src/main/java/com/rook/
├── config/
│   └── ProviderConfig.java      # 供应商配置
└── llm/
    ├── ChatMessage.java         # 聊天消息
    ├── LlmProvider.java         # Provider 接口
    ├── StreamCallback.java      # 流式回调
    ├── LlmException.java        # 自定义异常
    └── AnthropicProvider.java   # Anthropic 协议实现
```

------

## 运行验证

```java
// 测试代码
var config = new ProviderConfig("deepseek", "anthropic", "deepseek-v4-pro",
    "https://api.deepseek.com/anthropic", "sk-xxx");
var provider = new AnthropicProvider(config);
provider.stream(List.of(new ChatMessage("user", "你好")), token -> {
    System.out.print(token); // 逐字打印
});
```

**预期输出：**

```
你好！我是 DeepSeek，有什么可以帮你的吗？
```

（文字会一个字一个字地 "打" 出来，不是一次性出现）

**验证要点：**

1. 能正常连接 API，不报错
2. 输出是流式的，不是一次性全部出来
3. 中文没有乱码
4. 回复内容合理

------

## 常见坑

### 1. DeepSeek 返回 401 Unauthorized

**现象：** `LlmException: API Error (401): ...`

**原因：** 认证头搞错了。

- Anthropic 协议用的是 `x-api-key: sk-xxx`
- OpenAI 协议用的是 `Authorization: Bearer sk-xxx`
- 两个协议的认证头不一样，别搞混

**解决：** 检查你的 header 是不是 `x-api-key`。

### 2. SSE 解析到一半断了

**现象：** 输出了一半就停了，或者抛异常。

**原因：** `BufferedReader.readLine()` 返回 null 说明流结束了，应该正常退出，不要死循环。

**解决：** `while ((line = reader.readLine()) != null)` — 标准写法，null 就 break。

### 3. 中文乱码

**现象：** 输出的中文是乱码，比如 `浣犲ソ` 这种。

**原因：** Windows 默认编码是 GBK，不是 UTF-8。如果不显式指定编码，就会用系统默认编码。

**解决：** 所有涉及字符串和字节流转换的地方，都显式传 `StandardCharsets.UTF_8`：

- `BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8)`
- `new InputStreamReader(response.body(), StandardCharsets.UTF_8)`

### 4. stream 方法没有输出

**现象：** 调用了 stream 但什么都没打印。

**可能原因：**

- API Key 错了，返回 401，异常被吞了
- baseUrl 不对，404 了
- 事件类型判断错了，没进到回调里

**调试方法：** 先把每一行 SSE 原始数据打印出来，看看收到了什么。

### 5. 第一次请求很慢

**现象：** 第一次调用要等好几秒才开始输出。

**原因：**

- HttpClient 第一次建立连接，TCP 握手 + TLS 握手
- DNS 解析
- 模型加载（有些平台是按需加载的）

**正常的，第二次就快了。**

------

## 下一章

[03-SSE 流式解析.md](./03-SSE流式解析.md) — 给 OpenAI 协议也写一份 Provider，让 Rook 支持两种后端切换
