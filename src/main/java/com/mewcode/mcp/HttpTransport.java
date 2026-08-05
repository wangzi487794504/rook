package com.mewcode.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Streamable HTTP 传输 —— POST 请求，接收 SSE 或纯 JSON 响应。
 *
 * @author MewCode
 * @since 0.6.0
 */
public class HttpTransport implements McpTransport {

    private final HttpClient client;
    private final String url;
    private final Map<String, String> headers;

    public HttpTransport(String url, Map<String, String> headers) {
        this.client = HttpClient.newHttpClient();
        this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.headers = headers;
    }

    @Override
    public synchronized String send(String jsonRpcRequest) {
        try {
            var builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonRpcRequest, StandardCharsets.UTF_8));
            for (var e : headers.entrySet()) {
                builder.header(e.getKey(), e.getValue());
            }

            HttpResponse<String> resp = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());

            String body = resp.body();
            if (body == null || body.isBlank()) {
                return "{\"error\":{\"code\":-1,\"message\":\"HTTP " + resp.statusCode() + ": 空响应\"}}";
            }

            // 处理 SSE 格式：提取最后一条 data: 行或纯 JSON
            if (body.startsWith("data: ")) {
                String[] lines = body.split("\n");
                for (int i = lines.length - 1; i >= 0; i--) {
                    String line = lines[i].trim();
                    if (line.startsWith("data: ")) return line.substring(6);
                }
            }
            return body;
        } catch (Exception e) {
            return "{\"error\":{\"code\":-1,\"message\":\"HTTP请求失败: " + e.getMessage() + "\"}}";
        }
    }

    @Override
    public void close() { /* HTTP 无持久连接 */ }
}
