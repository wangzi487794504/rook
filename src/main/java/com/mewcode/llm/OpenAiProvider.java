package com.mewcode.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.config.ProviderConfig;
import com.mewcode.tools.ToolCall;
import com.mewcode.tools.ToolRegistry;

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
 * OpenAI 协议 LLM Provider，实现 Chat Completions API + SSE 流式调用 + 工具支持。
 *
 * @author MewCode
 * @since 0.1.0
 */
public class OpenAiProvider implements LlmProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProviderConfig config;
    private final HttpClient httpClient;
    private Usage lastUsage = Usage.ZERO;

    public OpenAiProvider(ProviderConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public String name() {
        return config.name();
    }

    @Override
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
            body.put("stream", true);

            // 转换消息
            List<Map<String, Object>> bodyMessages = convertMessages(messages);
            body.put("messages", bodyMessages);

            // ★ 携带工具定义（OpenAI 格式）
            if (tools != null && tools.size() > 0) {
                body.put("tools", toOpenAiTools(tools));
            }

            String bodyJson = MAPPER.writeValueAsString(body);

            String url = config.baseUrl().endsWith("/") ? config.baseUrl() + "chat/completions"
                    : config.baseUrl() + "/chat/completions";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + config.apiKey())
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

            // ★ 流式解析：同时处理文本 token 和 tool_calls
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
     * 解析 SSE 流，处理文本 token 和 tool_calls。
     */
    @SuppressWarnings("unchecked")
    private void parseSSE(java.io.InputStream body, StreamCallback callback) throws IOException {
        // tool_calls 累积：index → {id, functionName, arguments}
        Map<Integer, ToolCallBuilder> toolCallBuilders = new TreeMap<>();
        boolean hadToolCall = false;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6);
                if ("[DONE]".equals(data)) break;

                try {
                    Map<String, Object> event = MAPPER.readValue(data, Map.class);

                    // ★ 提取 usage（OpenAI 在最后一个 chunk 中返回，choices 为空）
                    Map<String, Object> usageObj = (Map<String, Object>) event.get("usage");
                    if (usageObj != null) {
                        int input = ((Number) usageObj.getOrDefault("prompt_tokens", 0)).intValue();
                        int output = ((Number) usageObj.getOrDefault("completion_tokens", 0)).intValue();
                        lastUsage = new Usage(input, output);
                        callback.onUsage(lastUsage);
                    }

                    List<Map<String, Object>> choices = (List<Map<String, Object>>) event.get("choices");
                    if (choices == null || choices.isEmpty()) continue;

                    Map<String, Object> choice = choices.get(0);
                    Map<String, Object> delta = (Map<String, Object>) choice.get("delta");

                    if (delta != null) {
                        // --- 文本 token ---
                        String content = (String) delta.get("content");
                        if (content != null) {
                            callback.onToken(content);
                        }

                        // --- tool_calls ---
                        List<Map<String, Object>> toolCalls =
                                (List<Map<String, Object>>) delta.get("tool_calls");
                        if (toolCalls != null) {
                            for (Map<String, Object> tc : toolCalls) {
                                int index = ((Number) tc.get("index")).intValue();
                                ToolCallBuilder builder = toolCallBuilders.computeIfAbsent(
                                        index, k -> new ToolCallBuilder());

                                String tcId = (String) tc.get("id");
                                if (tcId != null) builder.id = tcId;

                                Map<String, Object> func = (Map<String, Object>) tc.get("function");
                                if (func != null) {
                                    String funcName = (String) func.get("name");
                                    if (funcName != null) builder.functionName = funcName;

                                    String funcArgs = (String) func.get("arguments");
                                    if (funcArgs != null) builder.argsBuilder.append(funcArgs);
                                }
                            }
                        }
                    }

                    // finish_reason 表示流结束，tool_calls 已完整
                    String finishReason = (String) choice.get("finish_reason");
                    if (finishReason != null && !finishReason.isEmpty()) {
                        // 触发所有累积好的 tool_calls
                        for (ToolCallBuilder builder : toolCallBuilders.values()) {
                            if (builder.id != null && builder.functionName != null) {
                                try {
                                    Map<String, Object> input = MAPPER.readValue(
                                            builder.argsBuilder.toString(), Map.class);
                                    hadToolCall = true;
                                    callback.onToolCall(
                                            new ToolCall(builder.id, builder.functionName, input));
                                } catch (Exception e) {
                                    // JSON 解析失败
                                }
                            }
                        }
                        break;
                    }

                } catch (Exception e) {
                    // 单条 SSE 跳过
                }
            }
        }
    }

    /**
     * 将 ToolRegistry 转换为 OpenAI API 格式的工具列表。
     */
    private List<Map<String, Object>> toOpenAiTools(ToolRegistry registry) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Map<String, Object> def : registry.toToolDefinitions()) {
            Map<String, Object> openAiTool = new LinkedHashMap<>();
            openAiTool.put("type", "function");

            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", def.get("name"));
            function.put("description", def.get("description"));
            function.put("parameters", def.get("input_schema"));
            openAiTool.put("function", function);

            tools.add(openAiTool);
        }
        return tools;
    }

    /**
     * 转换消息列表为 OpenAI Chat Completions API 格式。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> convertMessages(List<ChatMessage> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ChatMessage msg : messages) {
            Map<String, Object> mm = new LinkedHashMap<>();

            if (msg.toolUseId() != null) {
                // ★ tool_result: OpenAI 格式 role=tool
                mm.put("role", "tool");
                mm.put("tool_call_id", msg.toolUseId());
                mm.put("content", msg.content());
            } else if (msg.toolCalls() != null) {
                // ★ assistant 带 tool_calls
                mm.put("role", "assistant");
                mm.put("content", msg.content());
                List<Map<String, Object>> toolCalls = new java.util.ArrayList<>();
                for (com.mewcode.tools.ToolCall tc : msg.toolCalls()) {
                    Map<String, Object> tcm = new LinkedHashMap<>();
                    tcm.put("id", tc.id());
                    tcm.put("type", "function");
                    Map<String, Object> func = new LinkedHashMap<>();
                    func.put("name", tc.name());
                    try {
                        func.put("arguments", MAPPER.writeValueAsString(tc.input()));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        func.put("arguments", "{}");
                    }
                    tcm.put("function", func);
                    toolCalls.add(tcm);
                }
                mm.put("tool_calls", toolCalls);
            } else {
                mm.put("role", msg.role());
                mm.put("content", msg.content());
            }
            result.add(mm);
        }
        return result;
    }

    /** tool_calls 累积构建器 */
    private static class ToolCallBuilder {
        String id;
        String functionName;
        StringBuilder argsBuilder = new StringBuilder();
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
