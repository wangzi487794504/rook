package com.rook.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rook.config.ProviderConfig;
import com.rook.tools.ToolCall;
import com.rook.tools.ToolRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Anthropic 协议 LLM Provider，实现 Messages API + SSE 流式调用 + 工具支持。
 *
 * @author wangzijie
 * @since 0.1.0
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class AnthropicProvider implements LlmProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProviderConfig config;
    private final HttpClient httpClient;
    private Usage lastUsage = Usage.ZERO;

    /** @return 方法返回值 */
    public AnthropicProvider(ProviderConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    /** @return 方法返回值 */
    public String name() {
        return config.name();
    }

    @Override
    /** @return 方法返回值 */
    public Usage lastUsage() {
        return lastUsage;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void stream(List<ChatMessage> messages, ToolRegistry tools, StreamCallback callback)
            throws LlmException {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", config.model());
            body.put("max_tokens", 4096);
            body.put("stream", true);

            // 转换消息 —— 支持 tool_result 格式
            List<Map<String, Object>> bodyMessages = convertMessages(messages);
            body.put("messages", bodyMessages);

            // ★ 携带工具定义
            if (tools != null && tools.size() > 0) {
                body.put("tools", tools.toToolDefinitions());
            }

            String bodyJson = MAPPER.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl() + "/messages"))
                    .header("x-api-key", config.apiKey())
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<java.io.InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

            int status = response.statusCode();
            if (status != 200) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new LlmException(buildErrorMessage(status, errorBody), status);
            }

            // ★ 重置用量
            lastUsage = Usage.ZERO;

            // ★ 流式解析：同时处理文本 token 和 tool_use
            parseSSE(response.body(), callback);

        } catch (LlmException e) {
            throw e;
        } catch (IOException e) {
            throw new LlmException("网络连接失败，请检查网络和 base_url: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("请求被中断", e);
        } catch (Exception e) {
            throw new LlmException("未知错误: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 SSE 流，同时处理文本 token 和 tool_use 事件。
     */
    @SuppressWarnings("unchecked")
    private void parseSSE(java.io.InputStream body, StreamCallback callback) throws IOException {
        // tool_use 累积状态
        String pendingToolUseId = null;
        String pendingToolName = null;
        StringBuilder inputJsonBuilder = new StringBuilder();
        boolean hadToolUse = false;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6);
                if ("[DONE]".equals(data)) break;

                try {
                    Map<String, Object> event = MAPPER.readValue(data, Map.class);
                    String type = (String) event.get("type");

                    // --- message_start: 缓存命中信息 ---
                    if ("message_start".equals(type)) {
                        Map<String, Object> message = (Map<String, Object>) event.get("message");
                        if (message != null) {
                            Map<String, Object> usageObj = (Map<String, Object>) message.get("usage");
                            if (usageObj != null) {
                                int cacheRead = ((Number) usageObj.getOrDefault(
                                        "cache_read_input_tokens", 0)).intValue();
                                int cacheCreation = ((Number) usageObj.getOrDefault(
                                        "cache_creation_input_tokens", 0)).intValue();
                                lastUsage = new Usage(
                                        lastUsage.inputTokens(),
                                        lastUsage.outputTokens(),
                                        cacheRead,
                                        cacheCreation);
                            }
                        }
                    }

                    // --- content_block_start: 新 content block 开始 ---
                    if ("content_block_start".equals(type)) {
                        Map<String, Object> contentBlock = (Map<String, Object>) event.get("content_block");
                        if (contentBlock == null) continue;
                        String blockType = (String) contentBlock.get("type");

                        if ("tool_use".equals(blockType)) {
                            // 记录 tool_use 信息
                            pendingToolUseId = (String) contentBlock.get("id");
                            pendingToolName = (String) contentBlock.get("name");
                            inputJsonBuilder.setLength(0);
                        }
                    }

                    // --- content_block_delta: 增量数据 ---
                    if ("content_block_delta".equals(type)) {
                        Map<String, Object> delta = (Map<String, Object>) event.get("delta");
                        if (delta == null) continue;

                        String deltaType = (String) delta.get("type");
                        if ("text_delta".equals(deltaType)) {
                            String text = (String) delta.get("text");
                            if (text != null) callback.onToken(text);
                        } else if ("input_json_delta".equals(deltaType)) {
                            String partialJson = (String) delta.get("partial_json");
                            if (partialJson != null) inputJsonBuilder.append(partialJson);
                        }
                    }

                    // --- content_block_stop: content block 结束 ---
                    if ("content_block_stop".equals(type)) {
                        // 如果之前在处理 tool_use，现在 JSON 收集完毕
                        if (pendingToolUseId != null && pendingToolName != null
                                && !inputJsonBuilder.isEmpty()) {
                            try {
                                Map<String, Object> input = MAPPER.readValue(
                                        inputJsonBuilder.toString(), Map.class);
                                hadToolUse = true;
                                callback.onToolCall(
                                        new ToolCall(pendingToolUseId, pendingToolName, input));
                            } catch (Exception e) {
                                // JSON 解析失败，忽略此次 tool_use
                            }
                            // 重置
                            pendingToolUseId = null;
                            pendingToolName = null;
                            inputJsonBuilder.setLength(0);
                        }
                    }

                    // --- message_delta: 包含 usage 信息 ---
                    if ("message_delta".equals(type)) {
                        Map<String, Object> usageObj = (Map<String, Object>) event.get("usage");
                        if (usageObj != null) {
                            int input = ((Number) usageObj.getOrDefault("input_tokens", 0)).intValue();
                            int output = ((Number) usageObj.getOrDefault("output_tokens", 0)).intValue();
                            lastUsage = new Usage(input, output);
                            callback.onUsage(lastUsage);
                        }
                    }

                    if ("message_stop".equals(type)) {
                        break;
                    }

                } catch (Exception e) {
                    // 单条 SSE 跳过
                }
            }
        }
    }

    /**
     * 转换消息列表为 API 格式，处理 tool_result 类型的消息。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> convertMessages(List<ChatMessage> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("role", msg.role());

            if (msg.toolUseId() != null) {
                // ★ tool_result: 合并连续的多条 tool_result 到同一条 user 消息
                List<Map<String, Object>> blocks = new java.util.ArrayList<>();
                blocks.add(buildToolResultBlock(msg));
                // 向后看：连续 tool_result 全合并进来
                while (i + 1 < messages.size() && messages.get(i + 1).toolUseId() != null) {
                    i++;
                    blocks.add(buildToolResultBlock(messages.get(i)));
                }
                mm.put("content", blocks);
            } else if (msg.toolCalls() != null) {
                // ★ assistant 带 tool_use: 构建 content blocks 数组
                List<Map<String, Object>> blocks = new java.util.ArrayList<>();
                if (msg.content() != null && !msg.content().isBlank()) {
                    Map<String, Object> textBlock = new LinkedHashMap<>();
                    textBlock.put("type", "text");
                    textBlock.put("text", msg.content());
                    blocks.add(textBlock);
                }
                for (ToolCall tc : msg.toolCalls()) {
                    Map<String, Object> toolBlock = new LinkedHashMap<>();
                    toolBlock.put("type", "tool_use");
                    toolBlock.put("id", tc.id());
                    toolBlock.put("name", tc.name());
                    toolBlock.put("input", tc.input());
                    blocks.add(toolBlock);
                }
                mm.put("content", blocks);
            } else {
                mm.put("content", msg.content());
            }
            result.add(mm);
        }
        return result;
    }

    /** 构造单个 tool_result content block */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildToolResultBlock(ChatMessage msg) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", msg.toolUseId());
        block.put("content", msg.content());
        return block;
    }

    private String buildErrorMessage(int status, String body) {
        return switch (status) {
            case 401 -> "认证失败，请检查 api_key";
            case 403 -> "访问被拒绝，请检查 api_key 权限";
            case 429 -> "请求频率过高，请稍后重试";
            case 500, 502, 503 -> "LLM 服务异常 (HTTP " + status + ")，请稍后重试";
            default -> "API 调用失败 (HTTP " + status + "): " + body;
        };
    }
}
