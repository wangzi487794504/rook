# 03 — OpenAI 协议适配 + Provider 工厂

## 这一章做什么

写完 OpenAI 协议的 Provider，再用工厂模式统一创建。现在 Rook 支持两个后端了，可以随时切换 DeepSeek、GPT、智谱、通义等各种模型。

**完成后你能做到：**

- 配置文件里改一行，就能切换不同的大模型
- 加新模型供应商，只需要写一个 Provider 类，上层代码不用动
- 同一个对话，能用不同模型对比回答质量

## 为什么需要

DeepSeek 兼容 Anthropic 协议，但市面上大部分模型走的是 OpenAI 协议：

- GPT-4o / GPT-5
- 智谱 GLM
- 月之暗面 Kimi
- 通义千问
- 百度文心一言
- ……

**两个 Provider 的意义：**

- 覆盖 99% 的大模型 API
- 想换模型就换，不用改业务代码
- 可以做 fallback：一个模型挂了自动切另一个

## 核心概念

### OpenAI Chat Completions API

OpenAI 定义的对话 API 格式，现在是行业事实标准，几乎所有国产模型都兼容。

**请求格式：**

```json
{
  "model": "gpt-4o",
  "stream": true,
  "messages": [
    {"role": "user", "content": "你好"}
  ]
}
```

**和 Anthropic 的核心区别：**

- 端点不同：`/chat/completions` vs `/messages`
- 认证头不同：`Authorization: Bearer` vs `x-api-key`
- SSE 数据结构不同
- 结束信号不同

### ProviderFactory（工厂模式）

根据配置自动创建对应的 Provider 对象，调用方不用关心具体是哪个实现。

**什么是工厂模式？**

就像一个 "生产车间"，你告诉它 "我要 anthropic 协议的"，它就给你一个 AnthropicProvider；你说 "我要 openai 协议的"，它就给你一个 OpenAiProvider。

**为什么需要工厂？**

- 调用方不用 `if (protocol == "anthropic") new AnthropicProvider()` 到处写
- 加新协议时，只需要在工厂里加一个 case，其他地方不用改
- 创建逻辑集中管理，不乱

### 接口统一

两个 Provider 都实现同一个 `LlmProvider` 接口，上层代码只认接口，不认具体实现。

**这就是面向接口编程的威力：**

```java
// 上层代码只关心接口
LlmProvider provider = ...; // 不管是 Anthropic 还是 OpenAI
provider.stream(messages, callback); // 调用方式完全一样
```

------

## 第一步：OpenAiProvider

```java
// llm/OpenAiProvider.java
package com.rook.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rook.config.ProviderConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class OpenAiProvider implements LlmProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ProviderConfig config;
    private final HttpClient httpClient;

    public OpenAiProvider(ProviderConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public String name() { return config.name(); }

    @Override
    @SuppressWarnings("unchecked")
    public void stream(List<ChatMessage> messages, StreamCallback callback) throws LlmException {
        try {
            // 1. 构建请求体
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", config.model());
            body.put("stream", true);
            body.put("messages", messages.stream().map(m -> {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("role", m.role());
                mm.put("content", m.content());
                return mm;
            }).toList());

            // 2. 构建请求
            String url = config.baseUrl() + "/chat/completions";
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

            // 3. 发送请求
            HttpResponse<java.io.InputStream> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofInputStream());

            int status = response.statusCode();
            if (status != 200) {
                String err = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new LlmException("API Error (" + status + "): " + err, status);
            }

            // 4. 逐行解析 SSE
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data: ")) continue;
                    String data = line.substring(6);
                    
                    // OpenAI 特有：[DONE] 表示结束
                    if ("[DONE]".equals(data)) break;

                    Map<String, Object> event = MAPPER.readValue(data, Map.class);
                    List<Map<String, Object>> choices =
                        (List<Map<String, Object>>) event.get("choices");
                    if (choices == null || choices.isEmpty()) continue;
                    
                    Map<String, Object> delta =
                        (Map<String, Object>) choices.get(0).get("delta");
                    if (delta == null) continue;
                    
                    String text = (String) delta.get("content");
                    if (text != null) callback.onToken(text);
                }
            }
        } catch (LlmException e) {
            throw e;
        } catch (IOException e) {
            throw new LlmException("网络错误: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("请求被中断", e);
        }
    }

    @Override
    public String complete(List<ChatMessage> messages) throws LlmException {
        StringBuilder sb = new StringBuilder();
        stream(messages, sb::append);
        return sb.toString();
    }
}
```

### 和 AnthropicProvider 的对比

|     位置     |            Anthropic            |             OpenAI             |
| :----------: | :-----------------------------: | :----------------------------: |
|   **URL**    |           `/messages`           |      `/chat/completions`       |
|  **认证头**  |       `x-api-key: sk-xxx`       | `Authorization: Bearer sk-xxx` |
|  **版本头**  | `anthropic-version: 2023-06-01` |             不需要             |
| **SSE 解析** |          `delta.text`           |   `choices[0].delta.content`   |
| **结束信号** |       `message_stop` 事件       |          `[DONE]` 行           |

### 逐段详解

#### 1. 类结构和构造器

```java
public class OpenAiProvider implements LlmProvider {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ProviderConfig config;
    private final HttpClient httpClient;

    public OpenAiProvider(ProviderConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newHttpClient();
    }
```

**和 AnthropicProvider 完全一样。**

- 同样的字段：MAPPER、config、httpClient
- 同样的构造逻辑

**为什么两个 Provider 结构这么像？**

因为干的事情本质上一样：发 HTTP 请求 → 解析 SSE → 回调 token。只是请求格式和响应格式不同。

#### 2. 构建请求体

```java
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", config.model());
            body.put("stream", true);
            body.put("messages", messages.stream().map(m -> {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("role", m.role());
                mm.put("content", m.content());
                return mm;
            }).toList());
```

**和 Anthropic 的区别：**

- 没有 `max_tokens` 字段（OpenAI 叫 `max_completion_tokens`，而且可以不填，有默认值）
- `messages` 的格式完全一样！这就是为什么 ChatMessage 类可以复用

**为什么 messages 格式一样？**

因为 OpenAI 先发明了 `role + content` 的消息格式，Anthropic 也借鉴了这个设计。所以两个协议的消息数组格式是兼容的。

#### 3. 构建请求

```java
            String url = config.baseUrl() + "/chat/completions";
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
```

**三处不同：**

1. **URL 不同**

   - Anthropic: `/messages`
   - OpenAI: `/chat/completions`

2. **认证头不同**

   - Anthropic: `x-api-key: sk-xxx`
   - OpenAI: `Authorization: Bearer sk-xxx`

   **这是最容易搞混的地方！** 用错了就会 401。

3. **没有版本头**

   - Anthropic 要求传 `anthropic-version`
   - OpenAI 不需要，URL 里的 `/v1` 就是版本

#### 4. SSE 解析（最关键的区别）

**Anthropic 的 SSE 长这样：**

```
data: {"type": "content_block_delta", "delta": {"type": "text_delta", "text": "你"}}
```

路径：`delta.text`

**OpenAI 的 SSE 长这样：**

```
data: {"choices": [{"delta": {"content": "你"}}]}
```

路径：`choices[0].delta.content`

**代码解析过程：**

```java
                    // 1. 跳过非 data: 开头的行
                    if (!line.startsWith("data: ")) continue;
                    String data = line.substring(6);
                    
                    // 2. OpenAI 特有：遇到 [DONE] 就结束
                    if ("[DONE]".equals(data)) break;

                    // 3. 解析 JSON
                    Map<String, Object> event = MAPPER.readValue(data, Map.class);
                    
                    // 4. 取 choices 数组
                    List<Map<String, Object>> choices =
                        (List<Map<String, Object>>) event.get("choices");
                    if (choices == null || choices.isEmpty()) continue;
                    
                    // 5. 取第一个 choice 的 delta
                    Map<String, Object> delta =
                        (Map<String, Object>) choices.get(0).get("delta");
                    if (delta == null) continue;
                    
                    // 6. 取 content 字段（就是 token 文本）
                    String text = (String) delta.get("content");
                    if (text != null) callback.onToken(text);
```

**为什么是 choices 数组？**

OpenAI 支持一次返回多个候选答案（n 参数），所以是数组。但通常我们只要第一个，所以 `choices.get(0)`。

**`[DONE]` 是什么？**

OpenAI 协议里，流式输出的最后一行是：

```
data: [DONE]
```

这不是 JSON，就是一个纯文本标记，表示所有内容都发完了。

**Anthropic 没有这个，它用的是 `message_stop` 事件类型。**

#### 5. complete () 方法

```
    @Override
    public String complete(List<ChatMessage> messages) throws LlmException {
        StringBuilder sb = new StringBuilder();
        stream(messages, sb::append);
        return sb.toString();
    }
```

**和 AnthropicProvider 完全一样！** 都是复用 stream 方法。

------

## 第二步：ProviderFactory（工厂模式）

```java
// llm/ProviderFactory.java
package com.rook.llm;

import com.rook.config.AppConfig;
import com.rook.config.ProviderConfig;

import java.util.LinkedHashMap;
import java.util.Map;

public class ProviderFactory {
    public static Map<String, LlmProvider> create(AppConfig config) {
        Map<String, LlmProvider> providers = new LinkedHashMap<>();
        for (ProviderConfig pc : config.providers()) {
            LlmProvider p = switch (pc.protocol().toLowerCase()) {
                case "anthropic" -> new AnthropicProvider(pc);
                case "openai" -> new OpenAiProvider(pc);
                default -> throw new IllegalArgumentException("未知协议: " + pc.protocol());
            };
            providers.put(pc.name(), p);
        }
        return providers;
    }
}
```

### 这是什么？

一个**静态工厂方法**，输入配置，输出一堆 Provider 对象。

**输入：** AppConfig（包含所有供应商的配置）

**输出：** Map<String, LlmProvider>（名字 → Provider 对象）

### 为什么需要工厂？

#### 没有工厂的写法（反面教材）

```java
// 到处都是这样的代码
if ("anthropic".equals(config.protocol())) {
    provider = new AnthropicProvider(config);
} else if ("openai".equals(config.protocol())) {
    provider = new OpenAiProvider(config);
} else {
    throw new ...;
}
```

**问题：**

- 这段 if-else 会重复出现在很多地方
- 加新协议时，要改很多地方
- 容易漏改

#### 有工厂的写法

```
// 只需要一行
LlmProvider provider = ProviderFactory.createSingle(config);
```

**好处：**

- 创建逻辑集中在一个地方
- 加新协议只改工厂，其他地方不动
- 调用方不用关心怎么创建的

### 代码逐行解释

```
    public static Map<String, LlmProvider> create(AppConfig config) {
```

- 静态方法，直接 `ProviderFactory.create(appConfig)` 调用
- 返回 Map，key 是 provider 名字，value 是 Provider 对象
- 用 Map 是为了方便按名字查找：`providers.get("deepseek")`

```
        Map<String, LlmProvider> providers = new LinkedHashMap<>();
```

- 用 LinkedHashMap 保持插入顺序
- 配置文件里写的顺序，就是 Map 里的顺序

```
        for (ProviderConfig pc : config.providers()) {
```

- 遍历配置里的所有供应商
- 每个配置创建一个 Provider 对象

```java
            LlmProvider p = switch (pc.protocol().toLowerCase()) {
                case "anthropic" -> new AnthropicProvider(pc);
                case "openai" -> new OpenAiProvider(pc);
                default -> throw new IllegalArgumentException("未知协议: " + pc.protocol());
            };
```

- Java 17+ 的 switch 表达式，比 if-else 干净
- `toLowerCase()` 是为了容错，配置里写 `Anthropic`、`ANTHROPIC`、`anthropic` 都能识别
- 遇到未知协议直接抛异常，启动时就报错，比运行时才发现好

```
            providers.put(pc.name(), p);
```

- 按名字存到 Map 里
- 名字就是配置里的 `name` 字段，比如 "deepseek"、"gpt"

```
        return providers;
```

- 返回全部创建好的 Provider

------

## 第三步：AppConfig

```java
// config/AppConfig.java
package com.rook.config;

import java.util.List;

public record AppConfig(
    List<ProviderConfig> providers,
    String defaultProvider
) {}
```

### 这是什么？

整个应用的配置根对象，包含：

- `providers` — 所有可用的模型供应商列表
- `defaultProvider` — 默认用哪个（名字）

### 为什么需要这个？

- 配置和代码分离
- 你可以配置 N 个模型，想用哪个用哪个
- 改配置不用重新编译

### 两个字段的作用

**`providers`（列表）：**

- 你配置了多少个模型，就有多少个 Provider
- 比如你可以同时配置 deepseek、gpt、glm 三个
- 想加新模型，就在配置文件里加一条

**`defaultProvider`（字符串）：**

- 默认用哪个模型的名字
- 比如 `default_provider: deepseek`
- 用户没指定用哪个时，就用默认的

------

## 第四步：配置文件

```yaml
# config.yaml
default_provider: deepseek

providers:
  - name: deepseek
    protocol: anthropic
    model: deepseek-v4-pro
    base_url: https://api.deepseek.com/anthropic
    api_key: sk-xxx

  - name: gpt
    protocol: openai
    model: gpt-4o
    base_url: https://api.openai.com/v1
    api_key: sk-xxx
```

### 逐字段解释

**顶层字段：**

- `default_provider` — 默认用哪个模型，填 provider 的 name

**每个 provider 的字段：**

|    字段    |               含义                |                 示例                 |
| :--------: | :-------------------------------: | :----------------------------------: |
|   `name`   |    给这个模型起个名字，随便起     |              `deepseek`              |
| `protocol` | 协议类型：`anthropic` 或 `openai` |             `anthropic`              |
|  `model`   |            具体模型名             |          `deepseek-v4-pro`           |
| `base_url` |            API 根地址             | `https://api.deepseek.com/anthropic` |
| `api_key`  |               密钥                |               `sk-xxx`               |

### 配置示例：加一个智谱 GLM

```
providers:
  - name: deepseek
    protocol: anthropic
    model: deepseek-v4-pro
    base_url: https://api.deepseek.com/anthropic
    api_key: sk-xxx

  - name: glm
    protocol: openai      # 智谱兼容 OpenAI 协议
    model: glm-4
    base_url: https://open.bigmodel.cn/api/paas/v4
    api_key: xxx
```

**看到没？加新模型只需要改配置文件，不用改 Java 代码！** 这就是面向接口 + 工厂模式的威力。

------

## Anthropic vs OpenAI 差异

|        差异点         |            Anthropic            |             OpenAI             |        说明        |
| :-------------------: | :-----------------------------: | :----------------------------: | :----------------: |
|       **端点**        |           `/messages`           |      `/chat/completions`       |     URL 不一样     |
|       **认证**        |       `x-api-key: sk-xxx`       | `Authorization: Bearer sk-xxx` |     最容易搞混     |
|      **版本头**       | `anthropic-version: 2023-06-01` |             不需要             |  Anthropic 必须传  |
| **max_tokens 字段名** |          `max_tokens`           |    `max_completion_tokens`     |  OpenAI 可以不填   |
|  **SSE token 路径**   |          `delta.text`           |   `choices[0].delta.content`   |    解析方式不同    |
|    **流结束信号**     |       `message_stop` 事件       |       `data: [DONE]` 行        | 判断结束的方式不同 |
|     **错误格式**      | `error.type` + `error.message`  |        `error.message`         |    报错时的结构    |

### SSE 对比示例

**Anthropic：**

```
data: {"type": "message_start", "message": {"id": "..."}}

data: {"type": "content_block_start", ...}

data: {"type": "content_block_delta", "delta": {"type": "text_delta", "text": "你"}}

data: {"type": "content_block_delta", "delta": {"type": "text_delta", "text": "好"}}

data: {"type": "content_block_stop", ...}

data: {"type": "message_delta", ...}

data: {"type": "message_stop"}
```

**OpenAI：**

```
data: {"id": "...", "choices": [{"delta": {"role": "assistant"}, "finish_reason": null}]}

data: {"choices": [{"delta": {"content": "你"}, "finish_reason": null}]}

data: {"choices": [{"delta": {"content": "好"}, "finish_reason": null}]}

data: {"choices": [{"delta": {}, "finish_reason": "stop"}]}

data: [DONE]
```

------

## 运行验证

### 测试 1：分别调用两个 Provider

```java
// 测试：分别调两个 Provider
var configDs = new ProviderConfig("deepseek", "anthropic", "deepseek-v4-pro",
    "https://api.deepseek.com/anthropic", "sk-xxx");
var deepseek = new AnthropicProvider(configDs);
deepseek.stream(List.of(new ChatMessage("user", "你好")),
    System.out::print);

System.out.println("\n---");

var configGpt = new ProviderConfig("gpt", "openai", "gpt-4o",
    "https://api.openai.com/v1", "sk-xxx");
var gpt = new OpenAiProvider(configGpt);
gpt.stream(List.of(new ChatMessage("user", "Hello")),
    System.out::print);
```

**预期：** 两个模型都能正常流式输出。

### 测试 2：用工厂创建

```java
// 用工厂创建所有 Provider
var appConfig = new AppConfig(
    List.of(configDs, configGpt),
    "deepseek"  // 默认用 deepseek
);
var providers = ProviderFactory.create(appConfig);

// 按名字取
LlmProvider defaultProvider = providers.get(appConfig.defaultProvider());
defaultProvider.stream(List.of(new ChatMessage("user", "你好")),
    System.out::print);
```

**预期：** 工厂正确创建了两个 Provider，默认的能正常工作。

------

## 常见坑（详细版）

### 1. OpenAI 兼容接口的 base_url 要带 `/v1`

**现象：** 404 Not Found

**原因：** OpenAI 协议的 API 都在 `/v1` 路径下。

- 正确：`https://api.deepseek.com/v1`
- 错误：`https://api.deepseek.com`

**注意：** Anthropic 协议的地址不带 `/v1`，比如 DeepSeek 的 Anthropic 兼容地址是 `https://api.deepseek.com/anthropic`。

**两个协议的 base_url 对比：**

|   协议    |            DeepSeek 地址             |
| :-------: | :----------------------------------: |
| Anthropic | `https://api.deepseek.com/anthropic` |
|  OpenAI   |    `https://api.deepseek.com/v1`     |

### 2. SSE `[DONE]` 行

**现象：** OpenAI 协议解析到最后报错，说 JSON 解析失败。

**原因：** OpenAI 流式输出的最后一行是 `data: [DONE]`，这不是 JSON，直接解析会报错。

**解决：** 解析 JSON 之前，先检查是不是 `[DONE]`，是的话直接 break。

**注意：** Anthropic 没有 `[DONE]`，它用的是 `message_stop` 事件。别在 AnthropicProvider 里检查 `[DONE]`，也别在 OpenAiProvider 里检查 `message_stop`。

### 3. 工厂模式不是必须的

**只有两个 Provider 时，直接 if-else 也行：**

```java
LlmProvider provider;
if ("anthropic".equals(protocol)) {
    provider = new AnthropicProvider(config);
} else {
    provider = new OpenAiProvider(config);
}
```

**什么时候需要工厂？**

- Provider 数量超过 3 个
- 创建逻辑比较复杂（需要初始化、依赖注入等）
- 需要在多个地方创建 Provider

**你现在的阶段，工厂模式主要是为了 "规范" 和 "可扩展性"。** 现在只有两个，以后加第三个、第四个的时候就爽了。

### 4. 不要硬编码 api_key

**错误做法：**

```
// 不要这样！密钥写在代码里
var config = new ProviderConfig("deepseek", "anthropic", "deepseek-v4-pro",
    "https://api.deepseek.com/anthropic", "sk-abc123def456");
```

**正确做法：**

- 从配置文件读
- 从环境变量读
- 从系统属性读

**原因：**

- 密钥写在代码里容易泄露（比如提交到 GitHub）
- 换 key 要改代码、重新编译
- 不同环境（开发 / 生产）用不同的 key

### 5. 两个 Provider 有大量重复代码

**观察：** AnthropicProvider 和 OpenAiProvider 大概 80% 的代码是一样的。

**要不要抽个父类？**

- 可以，但不急
- 现在两个 Provider，代码量不大，重复就重复了
- 等第三个、第四个 Provider 的时候再考虑抽象
- 过早抽象反而增加复杂度

**以后可以这样优化：**

- 抽一个 `AbstractHttpProvider` 父类
- 公共逻辑（HttpClient、异常处理、complete 方法）放父类
- 子类只实现差异部分（构建请求、解析 SSE）

------

## 架构图

```plaintext
┌─────────────────────────────────────────────────┐
│                  上层业务代码                      │
│         （只认识 LlmProvider 接口）                │
└──────────────────────┬──────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────┐
│              LlmProvider 接口                     │
│         stream() / complete() / name()           │
└──────────┬───────────────────────┬──────────────┘
           │                       │
           ▼                       ▼
┌──────────────────┐    ┌──────────────────┐
│ AnthropicProvider│    │  OpenAiProvider  │
│  (Anthropic 协议) │    │   (OpenAI 协议)   │
└──────────────────┘    └──────────────────┘
           │                       │
           ▼                       ▼
┌──────────────────┐    ┌──────────────────┐
│  DeepSeek API    │    │   GPT / GLM /    │
│  (Anthropic 兼容) │    │  Kimi 等 API     │
└──────────────────┘    └──────────────────┘
```

**核心思想：上层只依赖接口，不依赖具体实现。换模型 = 换实现类，上层不用改。**

------

## 下一章

[04 - 终端交互界面.md](./04-终端交互界面.md) — 用 JLine3 搭一个带命令补全和历史记录的终端交互界面
