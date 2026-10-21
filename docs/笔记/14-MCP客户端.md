# 14 — MCP 客户端：接入外部工具

## 这一章做什么

实现 **MCP (Model Context Protocol) 客户端**。通过 JSON-RPC 2.0 协议连接外部 MCP Server，自动发现和注册远端工具。

**完成后你能做到：**

- 内置 6 个工具不够用？接个 MCP Server，瞬间多几十个工具
- 想操作 Git？接 git MCP Server
- 想查数据库？接数据库 MCP Server
- 想调第三方 API？接对应的 MCP Server
- 不用改 Rook 代码，配置一下就行

## 为什么需要

### 内置工具的局限

我们内置了 6 个工具：read_file、write_file、edit_file、exec_command、glob、grep。

**但用户的需求是无限的：**

- "帮我看看 git 历史" → 需要 git 工具
- "帮我查一下数据库" → 需要数据库工具
- "帮我发个 Slack 消息" → 需要 Slack 工具
- "帮我查天气" → 需要天气工具
- ...

**不可能把所有工具都写死在 Rook 里。**

### MCP 的价值

MCP 是一个标准化协议，让 AI 客户端和工具提供方解耦：

```
Rook (MCP 客户端)  ←──JSON-RPC──→  MCP Server (工具提供方)
                                      ├─ Git 工具
                                      ├─ 数据库工具
                                      ├─ Slack 工具
                                      └─ ...
```

**好处：**

- Rook 不用关心工具怎么实现的，只管调用
- 工具提供方不用关心 AI 客户端是什么，只管提供工具
- 生态共享：别人写的 MCP Server，你直接用
- 无限扩展：想加新工具？接个 Server 就行

## 核心概念

### JSON-RPC 2.0

一种远程过程调用协议，用 JSON 格式。

**请求格式：**

```
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/list",
  "params": {}
}
```

**响应格式：**

```
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": { ... }
}
```

**通知格式（不需要响应）：**

```
{
  "jsonrpc": "2.0",
  "method": "notifications/initialized",
  "params": {}
}
```

### 两种传输方式

|   方式    |               原理               |       适用场景       |
| :-------: | :------------------------------: | :------------------: |
| **stdio** | 启动子进程，通过标准输入输出通信 | 本地工具、命令行工具 |
| **HTTP**  |        发 HTTP POST 请求         |  远程服务、云端 API  |

### 三阶段会话

连接 MCP Server 的标准流程：

```
1. initialize    → 握手，协商协议版本
2. notifications/initialized → 通知 Server 初始化完成
3. tools/list    → 获取所有工具列表
```

之后就可以调用 `tools/call` 来执行具体工具了。

------

## 第一步：McpTransport — 传输接口

抽象传输层，支持多种传输方式。

```java
// mcp/McpTransport.java
package com.rook.mcp;

import java.io.IOException;

public interface McpTransport {
    
    /**
     * 发送一个 JSON-RPC 请求，等待响应
     * @param jsonRpcRequest JSON-RPC 请求字符串
     * @return JSON-RPC 响应字符串
     */
    String send(String jsonRpcRequest) throws IOException;
    
    /**
     * 发送一个通知（不需要响应）
     */
    void sendNotification(String jsonRpcNotification) throws IOException;
    
    /**
     * 关闭连接
     */
    void close();
}
```

### 为什么用接口？

**面向接口编程，支持多种传输：**

- StdioTransport — 通过子进程 stdio 通信
- HttpTransport — 通过 HTTP 通信
- 以后还可以加 WebSocketTransport、...

上层代码只依赖接口，不用关心具体传输方式。

------

## 第二步：StdioTransport — 标准输入输出传输

最常用的传输方式，启动一个子进程，通过它的标准输入输出通信。

```java
// mcp/StdioTransport.java
package com.rook.mcp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class StdioTransport implements McpTransport {

    private final Process process;
    private final BufferedWriter writer;
    private final BufferedReader reader;
    private final AtomicInteger requestId = new AtomicInteger(0);

    public StdioTransport(String command, List<String> args) throws IOException {
        // 构建进程
        ProcessBuilder pb = new ProcessBuilder();
        pb.command().add(command);
        pb.command().addAll(args);
        
        // 启动子进程
        this.process = pb.start();
        
        // 获取子进程的输出流（我们写进去的）和输入流（我们读出来的）
        this.writer = new BufferedWriter(
            new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)
        );
        this.reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
        );
    }

    @Override
    public synchronized String send(String jsonRpcRequest) throws IOException {
        // 写一行请求
        writer.write(jsonRpcRequest);
        writer.newLine();
        writer.flush();
        
        // 读一行响应
        // 注意：Server 可能先发通知，再发响应，要按 id 匹配
        String line;
        while ((line = reader.readLine()) != null) {
            // 检查是不是响应（有 id 字段且不是通知）
            if (isResponse(line)) {
                return line;
            }
            // 是通知的话，跳过，继续读
        }
        
        throw new IOException("连接已关闭，未收到响应");
    }

    @Override
    public void sendNotification(String jsonRpcNotification) throws IOException {
        writer.write(jsonRpcNotification);
        writer.newLine();
        writer.flush();
    }

    @Override
    public void close() {
        process.destroy();
    }

    /**
     * 判断一行 JSON 是不是响应（有 id 字段）
     */
    private boolean isResponse(String line) {
        // 简单判断：包含 "id" 字段
        // 实际应该用 JSON 解析
        return line.contains("\"id\"");
    }

    public int nextId() {
        return requestId.incrementAndGet();
    }
}
```

### 逐段详解

#### 1. 启动子进程

```
ProcessBuilder pb = new ProcessBuilder();
pb.command().add(command);
pb.command().addAll(args);
this.process = pb.start();
```

启动一个外部进程，比如 `npx -y @modelcontextprotocol/server-everything`。

**这个进程就是 MCP Server。**

#### 2. 获取输入输出流

```
this.writer = new BufferedWriter(
    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)
);
this.reader = new BufferedReader(
    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
);
```

**注意方向：**

- `process.getOutputStream()` — 子进程的输入，我们往这里写 → 我们的 writer
- `process.getInputStream()` — 子进程的输出，我们从这里读 → 我们的 reader

容易搞反，记住：**从子进程的角度看，OutputStream 是它的输入，InputStream 是它的输出。**

#### 3. send 方法

```java
public synchronized String send(String jsonRpcRequest) throws IOException {
    writer.write(jsonRpcRequest);
    writer.newLine();
    writer.flush();
    
    String line;
    while ((line = reader.readLine()) != null) {
        if (isResponse(line)) {
            return line;
        }
        // 通知行，跳过
    }
    
    throw new IOException("连接已关闭");
}
```

**为什么用 synchronized？**

- 防止多个线程同时发请求，响应乱掉
- stdio 是顺序的，发一个等一个，不能并发

**为什么要跳过通知行？**

Server 可能先发一些通知（比如日志、进度），再发响应。

我们要找到对应我们请求的那一行响应。

**简化版的问题：**

这里用 `contains("\"id\"")` 简单判断，实际应该用 JSON 解析，检查：

- 有 `id` 字段
- id 值和我们发的请求 id 一致

#### 4. 为什么用 newLine ()？

MCP 的 stdio 传输约定：**每条消息一行，用换行符分隔。**

所以发完 JSON 后要加个换行，Server 才知道一条消息结束了。

------

## 第三步：McpSession — 会话管理

管理和 MCP Server 的会话，封装三阶段初始化和工具调用。

```java
// mcp/McpSession.java
package com.rook.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rook.tools.ToolResult;
import java.io.IOException;
import java.util.*;

public class McpSession {

    private final McpTransport transport;
    private final String serverName;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpSession(String serverName, McpTransport transport) {
        this.serverName = serverName;
        this.transport = transport;
    }

    /**
     * 三阶段初始化，返回工具列表
     */
    public List<McpToolDef> initialize() throws IOException {
        // ── 阶段 1：initialize 握手 ──
        Map<String, Object> initParams = Map.of(
            "protocolVersion", "2024-11-05",
            "capabilities", Map.of(),
            "clientInfo", Map.of(
                "name", "rook",
                "version", "0.9.0"
            )
        );
        JsonRpcResponse initResp = blockingSend("initialize", initParams);
        // 检查协议版本是否兼容
        // ...

        // ── 阶段 2：发送 initialized 通知 ──
        // 通知不需要 id，不需要响应
        Map<String, Object> notify = Map.of(
            "jsonrpc", "2.0",
            "method", "notifications/initialized",
            "params", Map.of()
        );
        transport.sendNotification(mapper.writeValueAsString(notify));

        // ── 阶段 3：获取工具列表 ──
        JsonRpcResponse toolsResp = blockingSend("tools/list", Map.of());
        return parseTools(toolsResp.result());
    }

    /**
     * 调用一个工具
     */
    public ToolResult callTool(String toolName, Map<String, Object> params) throws IOException {
        Map<String, Object> callParams = Map.of(
            "name", toolName,
            "arguments", params
        );
        JsonRpcResponse resp = blockingSend("tools/call", callParams);
        return parseToolResult(resp.result());
    }

    /**
     * 发送一个 JSON-RPC 请求，等待响应
     */
    private JsonRpcResponse blockingSend(String method, Map<String, Object> params) 
            throws IOException {
        int id = transport instanceof StdioTransport stdio ? stdio.nextId() : 1;
        
        Map<String, Object> request = Map.of(
            "jsonrpc", "2.0",
            "id", id,
            "method", method,
            "params", params
        );
        
        String json = mapper.writeValueAsString(request);
        String responseJson = transport.send(json);
        
        return mapper.readValue(responseJson, JsonRpcResponse.class);
    }

    /**
     * 解析工具列表
     */
    private List<McpToolDef> parseTools(Map<String, Object> result) {
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        List<McpToolDef> defs = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            defs.add(new McpToolDef(
                (String) tool.get("name"),
                (String) tool.get("description"),
                (Map<String, Object>) tool.get("inputSchema")
            ));
        }
        return defs;
    }

    /**
     * 解析工具调用结果
     */
    private ToolResult parseToolResult(Map<String, Object> result) {
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> item : content) {
            if ("text".equals(item.get("type"))) {
                sb.append(item.get("text"));
            }
        }
        
        boolean isError = Boolean.TRUE.equals(result.get("isError"));
        if (isError) {
            return ToolResult.error(sb.toString());
        } else {
            return ToolResult.ok(sb.toString());
        }
    }

    public void close() {
        transport.close();
    }

    public String serverName() {
        return serverName;
    }
}
```

### 逐段详解

#### 1. 三阶段初始化

```
客户端                          Server
  │                               │
  │── initialize 请求 ──────────→ │  阶段1：握手
  │←── initialize 响应 ──────────│
  │                               │
  │── notifications/initialized ─→│  阶段2：通知初始化完成
  │                               │
  │── tools/list 请求 ──────────→ │  阶段3：获取工具列表
  │←── tools/list 响应 ──────────│
  │                               │
```

##### 阶段 1：initialize

```
Map<String, Object> initParams = Map.of(
    "protocolVersion", "2024-11-05",
    "capabilities", Map.of(),
    "clientInfo", Map.of(
        "name", "rook",
        "version", "0.9.0"
    )
);
```

**告诉 Server：**

- 我用的协议版本是 2024-11-05
- 我支持的能力（这里为空，最简单）
- 我是谁（rook v0.9.0）

**Server 会返回：**

- 它支持的协议版本
- 它的能力
- 它的名字和版本

**协议版本协商：**

如果版本不兼容，可能要降级或者报错。

##### 阶段 2：notifications/initialized

```java
Map<String, Object> notify = Map.of(
    "jsonrpc", "2.0",
    "method", "notifications/initialized",
    "params", Map.of()
);
transport.sendNotification(mapper.writeValueAsString(notify));
```

**这是一个通知，不需要响应。**

告诉 Server："我初始化好了，你可以开始了。"

**为什么需要这个？**

有些 Server 在收到这个通知之前，不会接受其他请求。

这是协议规定的流程。

##### 阶段 3：tools/list

```
JsonRpcResponse toolsResp = blockingSend("tools/list", Map.of());
return parseTools(toolsResp.result());
```

获取 Server 提供的所有工具列表。

**返回的工具列表长这样：**

```json
{
  "tools": [
    {
      "name": "echo",
      "description": "Echo back the input",
      "inputSchema": {
        "type": "object",
        "properties": {
          "message": { "type": "string" }
        }
      }
    },
    ...
  ]
}
```

每个工具有：名字、描述、参数 schema。和我们内置的 Tool 接口格式一样！

#### 2. callTool — 调用工具

```java
public ToolResult callTool(String toolName, Map<String, Object> params) throws IOException {
    Map<String, Object> callParams = Map.of(
        "name", toolName,
        "arguments", params
    );
    JsonRpcResponse resp = blockingSend("tools/call", callParams);
    return parseToolResult(resp.result());
}
```

**调用 tools/call 方法，传入工具名和参数。**

**返回结果格式：**

```
{
  "content": [
    { "type": "text", "text": "结果内容" }
  ],
  "isError": false
}
```

- `content` 是一个数组，可以有文本、图片等
- `isError` 表示是不是错误

#### 3. blockingSend — 发送请求

```java
private JsonRpcResponse blockingSend(String method, Map<String, Object> params) {
    int id = ...; // 生成唯一 id
    
    Map<String, Object> request = Map.of(
        "jsonrpc", "2.0",
        "id", id,
        "method", method,
        "params", params
    );
    
    String json = mapper.writeValueAsString(request);
    String responseJson = transport.send(json);
    
    return mapper.readValue(responseJson, JsonRpcResponse.class);
}
```

**标准的 JSON-RPC 请求：**

- `jsonrpc`: "2.0" — 固定
- `id`: 请求 id，用于匹配响应
- `method`: 方法名
- `params`: 参数

------

## 第四步：McpToolAdapter — 工具适配器

把 MCP 工具包装成我们的 Tool 接口，这样可以注册到 ToolRegistry 里，和内置工具一样用。

```java
// mcp/McpToolAdapter.java
package com.rook.mcp;

import com.rook.tools.Tool;
import com.rook.tools.ToolResult;
import java.util.Map;

public class McpToolAdapter implements Tool {

    private final McpToolDef toolDef;
    private final McpSession session;
    private final String prefixedName;  // 加前缀的名字，避免冲突

    public McpToolAdapter(McpToolDef toolDef, McpSession session) {
        this.toolDef = toolDef;
        this.session = session;
        // 名字加前缀：mcp_<server>_<tool>
        this.prefixedName = "mcp_" + session.serverName() + "_" + toolDef.name();
    }

    @Override
    public String name() {
        return prefixedName;
    }

    @Override
    public String description() {
        return toolDef.description();
    }

    @Override
    public Map<String, Object> inputSchema() {
        return toolDef.inputSchema();
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        try {
            // 调用 MCP Server 的工具
            // 注意：传给 Server 的是原始工具名，不带前缀
            return session.callTool(toolDef.name(), params);
        } catch (Exception e) {
            return ToolResult.error("MCP 工具调用失败: " + e.getMessage());
        }
    }

    /**
     * 原始工具名（不带前缀）
     */
    public String originalName() {
        return toolDef.name();
    }
}
```

### 为什么需要适配器？

**我们的 Tool 接口：**

```
public interface Tool {
    String name();
    String description();
    Map<String, Object> inputSchema();
    ToolResult execute(Map<String, Object> params);
}
```

**MCP 工具的格式：**

```
public record McpToolDef(
    String name,
    String description,
    Map<String, Object> inputSchema
) {}
```

格式很像，但 MCP 工具没有 execute 方法，它的执行要通过网络调用。

**适配器做的事：**

- 实现 Tool 接口
- name/description/inputSchema 直接转发给 McpToolDef
- execute 方法里，通过 McpSession 调用远端工具

**这就是适配器模式：把一个接口转换成另一个接口。**

### 为什么名字要加前缀？

```
this.prefixedName = "mcp_" + session.serverName() + "_" + toolDef.name();
```

**防止工具名冲突。**

比如：

- 内置工具叫 `read_file`
- MCP Server 也提供了一个 `read_file`
- 如果都叫 `read_file`，注册的时候就冲突了

加前缀后：

- 内置：`read_file`
- MCP everything Server 的：`mcp_everything_read_file`
- 不会冲突了

**注意：** 调用远端工具的时候，要用原始名字（不带前缀），因为 Server 只认识原始名字。

------

## 第五步：配置与注册 — McpManager

管理所有 MCP Server，加载配置、初始化、注册工具。

### 配置文件格式

```
# .rook/mcp.yaml
servers:
  # 第一个 Server：everything（测试用，提供各种简单工具）
  everything:
    type: stdio
    command: cmd
    args: ["/c", "npx", "-y", "@modelcontextprotocol/server-everything"]
  
  # 第二个 Server：Git 工具
  git:
    type: stdio
    command: npx
    args: ["-y", "@modelcontextprotocol/server-git"]
  
  # 第三个 Server：远程 HTTP Server
  remote-api:
    type: http
    url: "https://example.com/mcp"
```

### McpManager 实现

```
// mcp/McpManager.java
package com.rook.mcp;

import com.rook.tools.ToolRegistry;
import org.yaml.snakeyaml.Yaml;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class McpManager {

    private final Path configPath;
    private final ToolRegistry toolRegistry;
    private final List<McpSession> sessions = new ArrayList<>();

    public McpManager(Path configPath, ToolRegistry toolRegistry) {
        this.configPath = configPath;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 发现并初始化所有 MCP Server，注册工具
     */
    public void discover() throws IOException {
        if (!Files.exists(configPath)) {
            System.out.println("[MCP] 未找到配置文件，跳过");
            return;
        }

        // 加载配置
        Map<String, Object> config = new Yaml().load(Files.readString(configPath));
        Map<String, Map<String, Object>> servers = 
            (Map<String, Map<String, Object>>) config.get("servers");

        if (servers == null || servers.isEmpty()) {
            return;
        }

        // 并行初始化所有 Server
        ExecutorService executor = Executors.newFixedThreadPool(servers.size());
        List<Future<?>> futures = new ArrayList<>();

        for (Map.Entry<String, Map<String, Object>> entry : servers.entrySet()) {
            String serverName = entry.getKey();
            Map<String, Object> serverConfig = entry.getValue();

            futures.add(executor.submit(() -> {
                try {
                    initializeServer(serverName, serverConfig);
                } catch (Exception e) {
                    System.err.println("[MCP:" + serverName + "] 初始化失败: " + e.getMessage());
                }
            }));
        }

        // 等待所有初始化完成
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                // 单个 Server 失败不影响其他
            }
        }

        executor.shutdown();
    }

    /**
     * 初始化单个 Server
     */
    private void initializeServer(String serverName, Map<String, Object> config) throws IOException {
        String type = (String) config.getOrDefault("type", "stdio");
        
        McpTransport transport;
        if ("stdio".equals(type)) {
            String command = (String) config.get("command");
            List<String> args = (List<String>) config.get("args");
            transport = new StdioTransport(command, args);
        } else if ("http".equals(type)) {
            String url = (String) config.get("url");
            transport = new HttpTransport(url);
        } else {
            throw new IllegalArgumentException("未知的 MCP 传输类型: " + type);
        }

        McpSession session = new McpSession(serverName, transport);
        List<McpToolDef> tools = session.initialize();

        // 注册每个工具
        for (McpToolDef toolDef : tools) {
            McpToolAdapter adapter = new McpToolAdapter(toolDef, session);
            toolRegistry.register(adapter);
        }

        sessions.add(session);
        System.out.println("[MCP:" + serverName + "] 已注册 " + tools.size() + " 个工具");
    }

    /**
     * 关闭所有会话
     */
    public void closeAll() {
        for (McpSession session : sessions) {
            session.close();
        }
        sessions.clear();
    }
}
```

### 逐段详解

#### 1. 加载配置

```
Map<String, Object> config = new Yaml().load(Files.readString(configPath));
Map<String, Map<String, Object>> servers = 
    (Map<String, Map<String, Object>>) config.get("servers");
```

用 SnakeYAML 解析 mcp.yaml，拿到所有 Server 配置。

#### 2. 并行初始化

```
ExecutorService executor = Executors.newFixedThreadPool(servers.size());
List<Future<?>> futures = new ArrayList<>();

for (Map.Entry<String, Map<String, Object>> entry : servers.entrySet()) {
    futures.add(executor.submit(() -> {
        initializeServer(serverName, serverConfig);
    }));
}

for (Future<?> f : futures) {
    f.get();
}
```

**为什么并行？**

- 每个 Server 启动都要时间（比如 npx 要下载、启动）
- 串行的话，10 个 Server 要等很久
- 并行初始化，快很多

**单个失败不影响其他：**

- 每个 Server 的初始化都在 try-catch 里
- 一个挂了，其他继续

#### 3. 初始化单个 Server

```
private void initializeServer(String serverName, Map<String, Object> config) {
    String type = (String) config.getOrDefault("type", "stdio");
    
    McpTransport transport;
    if ("stdio".equals(type)) {
        transport = new StdioTransport(command, args);
    } else if ("http".equals(type)) {
        transport = new HttpTransport(url);
    }
    
    McpSession session = new McpSession(serverName, transport);
    List<McpToolDef> tools = session.initialize();
    
    for (McpToolDef toolDef : tools) {
        toolRegistry.register(new McpToolAdapter(toolDef, session));
    }
}
```

**流程：**

1. 根据 type 创建对应的 Transport
2. 创建 Session
3. 调用 initialize ()（三阶段握手 + 获取工具列表）
4. 每个工具包装成 McpToolAdapter，注册到 ToolRegistry

------

## 完整流程

```
启动 Rook
    │
    ▼
McpManager.discover()
    │
    ├─ 加载 .rook/mcp.yaml
    ├─ 并行初始化每个 Server
    │   │
    │   ├─ StdioTransport 启动子进程
    │   ├─ McpSession.initialize()
    │   │   ├─ initialize 握手
    │   │   ├─ notifications/initialized 通知
    │   │   └─ tools/list 获取工具列表
    │   └─ 每个工具包装成 McpToolAdapter，注册到 ToolRegistry
    │
    ▼
[MCP:everything] 已注册 12 个工具
[MCP:git] 已注册 8 个工具
    │
    ▼
用户输入："帮我用 MCP 工具算 42+58"
    │
    ▼
AgentLoop
    │
    ├─ 调 LLM，传入所有工具定义（包括 MCP 工具）
    ├─ LLM 决定调用 mcp_everything_get-sum(a=42, b=58)
    ├─ ToolExecutor 执行
    │   └─ McpToolAdapter.execute()
    │       └─ McpSession.callTool("get-sum", {a:42, b:58})
    │           └─ transport.send(tools/call 请求)
    │               └─ MCP Server 执行，返回结果 100
    ├─ 回灌结果
    └─ LLM 回答："42 + 58 = 100"
```

------

## 运行验证

### 启动时

```
> rook
[MCP:everything] 已注册 12 个工具
[MCP:git] 已注册 8 个工具
> _
```

### 查看工具列表

```
> :tools
内置工具:
  read_file, write_file, edit_file, exec_command, glob, grep

MCP 工具:
  mcp_everything_echo
  mcp_everything_get-sum
  mcp_everything_get-text
  mcp_git_log
  mcp_git_diff
  ...
```

### 调用 MCP 工具

```
> 帮我用 MCP 工具算 42+58

[调用工具: mcp_everything_get-sum]
[工具结果: 100]

42 + 58 = 100
```

------

## 常见坑（详细版）

### 1. Windows 上 npx 找不到

**现象：** 配置里写 `command: npx`，启动报错 "Cannot find program: npx"。

**原因：**`ProcessBuilder` 不走 shell，不解析 PATH 里的 `.cmd` 脚本。

Windows 上 `npx` 实际上是 `npx.cmd`，ProcessBuilder 找不到。

**解决：** 用 `cmd /c` 包装：

```
servers:
  everything:
    type: stdio
    command: cmd
    args: ["/c", "npx", "-y", "@modelcontextprotocol/server-everything"]
```

`cmd /c` 会启动命令行，然后在命令行里执行 npx，这时候 PATH 解析是正常的。

### 2. 通知行干扰响应读取

**现象：** 有时候读响应读到的是通知，解析失败。

**原因：** Server 可能先发通知（日志、进度），再发响应。

如果直接 readLine () 就返回，可能拿到的是通知。

**解决：** 按 id 匹配：

```
while ((line = reader.readLine()) != null) {
    JsonNode json = mapper.readTree(line);
    // 有 id 字段，且 id 匹配我们的请求 → 是响应
    if (json.has("id") && json.get("id").asInt() == expectedId) {
        return line;
    }
    // 否则是通知，跳过继续读
}
```

### 3. 工具名冲突

**现象：** 注册工具时报错 "工具已存在"。

**原因：** MCP 工具名和内置工具名一样，或者两个 MCP Server 提供了同名工具。

**解决：** 加前缀：

```
String prefixedName = "mcp_" + serverName + "_" + toolName;
```

- 内置：`read_file`
- MCP everything：`mcp_everything_read_file`
- MCP other：`mcp_other_read_file`

不会冲突了。

### 4. stdio 传输的并发问题

**现象：** 并发调用 MCP 工具，响应乱掉。

**原因：** stdio 是顺序的，发一个等一个，不能并发。

如果多个线程同时发，响应会乱。

**解决：**

- send 方法加 synchronized
- 或者用队列，请求排队执行
- 或者每个请求等对应 id 的响应

### 5. 子进程挂了

**现象：** 调用 MCP 工具没反应，或者报错 "连接已关闭"。

**原因：** MCP Server 子进程崩溃了（比如代码异常、OOM）。

**解决：**

- 检查进程是否还活着：`process.isAlive()`
- 读 stderr 看错误信息：`process.getErrorStream()`
- 自动重启：发现挂了就重新启动
- 或者告诉用户 "MCP Server 已崩溃，请检查"

### 6. 启动超时

**现象：** 启动 MCP Server 等很久，甚至超时。

**原因：**

- npx 第一次要下载包，慢
- Server 启动慢
- 网络问题

**解决：**

- 加超时：initialize 最多等 30 秒
- 超时就跳过这个 Server，不影响其他
- 提示用户 "MCP Server xxx 启动超时，已跳过"

### 7. 参数 schema 不兼容

**现象：** MCP 工具的 inputSchema 和 LLM 期望的格式不一样，LLM 不会调用。

**原因：** MCP 的 schema 格式可能和我们内置的略有差异。

**解决：**

- 适配器里做格式转换
- 或者统一用标准的 JSON Schema
- 测试不同的 MCP Server，确保兼容

### 8. 大结果处理

**现象：** MCP 工具返回很大的结果，撑爆上下文。

**原因：** 有些工具返回很多内容（比如读大文件、查数据库）。

**解决：**

- 结果截断，超过一定长度就截断
- 或者分页返回
- 或者提示用户结果太长，用更精确的查询

------

## 架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                           Rook (客户端)                          │
│                                                                 │
│  ┌──────────────┐                                               │
│  │ ToolRegistry │ ← 所有工具都注册在这里                          │
│  │              │                                               │
│  │  read_file   │ ← 内置工具                                    │
│  │  write_file  │                                               │
│  │  exec_command│                                               │
│  │  ...         │                                               │
│  │              │                                               │
│  │  mcp_everything_echo     │ ← MCP 工具（适配器）               │
│  │  mcp_everything_get-sum  │                                   │
│  │  mcp_git_log             │                                   │
│  │  ...                     │                                   │
│  └──────┬───────┘                                               │
│         │                                                       │
│         ▼                                                       │
│  ┌──────────────┐                                               │
│  │McpToolAdapter│ ← 把 MCP 工具包装成 Tool 接口                  │
│  └──────┬───────┘                                               │
│         │                                                       │
│         ▼                                                       │
│  ┌──────────────┐                                               │
│  │  McpSession  │ ← 会话管理，三阶段初始化，工具调用              │
│  └──────┬───────┘                                               │
│         │                                                       │
│         ▼                                                       │
│  ┌──────────────┐                                               │
│  │ McpTransport │ ← 传输层接口                                  │
│  └──────┬───────┘                                               │
│         │                                                       │
│    ┌────┴────┐                                                  │
│    ▼         ▼                                                  │
│ ┌───────┐ ┌──────────┐                                          │
│ │Stdio  │ │  HTTP    │                                          │
│ │Transport│ │Transport│                                         │
│ └───┬───┘ └────┬─────┘                                          │
└─────┼──────────┼────────────────────────────────────────────────┘
      │          │
      │ stdio    │ HTTP
      ▼          ▼
┌──────────┐ ┌──────────┐
│ 子进程   │ │ 远程服务 │
│ MCP      │ │ MCP      │
│ Server   │ │ Server   │
└──────────┘ └──────────┘
```

------

## 下一章

[15 - 上下文管理.md](./15-上下文管理.md) — Token 预算控制，对话再长也不溢出
