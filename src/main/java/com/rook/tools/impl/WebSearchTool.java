package com.rook.tools.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rook.tools.Tool;
import com.rook.tools.ToolResult;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 联网搜索工具 —— Bing Web Search API（主） + HTML 抓取（备）。
 *
 * @author wangzijie
 * @since 0.12.0
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class WebSearchTool implements Tool {

    private static final String BRAVE_API = "https://api.search.brave.com/res/v1/web/search";
    private static final String BING_HTML = "https://cn.bing.com/search";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 配置注入的 key（优先于环境变量，可空） */
    private final String glmKey;
    private final String braveKey;

    /** 无参构造：仅从环境变量读取 key（兼容旧用法与测试）。 */
    public WebSearchTool() {
        this(null, null);
    }

    /** 注入配置中的 key（可空），执行时优先于环境变量。 */
    public WebSearchTool(String glmKey, String braveKey) {
        this.glmKey = glmKey;
        this.braveKey = braveKey;
    }

    @Override public String name() { return "web_search"; }

    @Override
    /** @return 方法返回值 */
    public String description() {
        return "搜索互联网。参数: query(必填), count(默认5, 最大10)";
    }

    @Override
    /** @return 方法返回值 */
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "搜索关键词"),
                        "count", Map.of("type", "integer", "description", "条数(默认5)")),
                "required", List.of("query"));
    }

    @Override
    /** @return 方法返回值 */
    public ToolResult execute(Map<String, Object> params) {
        String query = (String) params.get("query");
        if (query == null || query.isBlank()) return ToolResult.error("缺少 query");
        int count = Math.min(params.containsKey("count")
                ? ((Number) params.get("count")).intValue() : 5, 10);

        String apiKey = firstNonBlank(glmKey, System.getenv("GLM_SEARCH_API_KEY"));
        if (apiKey != null) {
            return searchGlm(query, count, apiKey);
        }
        apiKey = firstNonBlank(braveKey, System.getenv("BRAVE_SEARCH_API_KEY"));
        if (apiKey != null) {
            return searchApi(query, count, apiKey);
        }
        return searchHtml(query, count);
    }

    /** 智谱 GLM Web Search API（独立搜索端点，非 Chat） */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private ToolResult searchGlm(String query, int count, String apiKey) {
        try {
            Map body = new java.util.LinkedHashMap<>();
            body.put("search_query", query);
            body.put("search_engine", "search_pro");
            body.put("search_intent", false);
            body.put("count", count);
            body.put("search_recency_filter", "noLimit");

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://open.bigmodel.cn/api/paas/v4/web_search"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            String json = client.send(req, HttpResponse.BodyHandlers.ofString()).body();
            Map result = MAPPER.readValue(json, Map.class);
            List searchResult = (List) result.get("search_result");
            if (searchResult == null || searchResult.isEmpty()) {
                return ToolResult.ok("未找到结果。换关键词试试。");
            }

            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (Object item : searchResult) {
                if (++n > count) break;
                if (!(item instanceof Map page)) continue;
                sb.append(n).append(". **").append(page.getOrDefault("title", "")).append("**\n");
                sb.append("   ").append(page.getOrDefault("link", "")).append("\n");
                sb.append("   ").append(page.getOrDefault("content", "")).append("\n\n");
            }
            return sb.toString().isBlank()
                    ? ToolResult.ok("未找到结果")
                    : ToolResult.ok(sb.toString());
        } catch (Exception e) {
            return searchHtml(query, count);
        }
    }

    /** Bing API：结构化 JSON */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private ToolResult searchApi(String query, int count, String apiKey) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(8)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BRAVE_API + "?q=" + encoded + "&count=" + count))
                    .header("Accept", "application/json")
                    .header("X-Subscription-Token", apiKey)
                    .timeout(Duration.ofSeconds(8)).GET().build();

            String json = client.send(req, HttpResponse.BodyHandlers.ofString()).body();
            Map result = MAPPER.readValue(json, Map.class);
            Object wpObj = result.get("webPages");
            if (!(wpObj instanceof Map wp)) return searchHtml(query, count);
            Object val = wp.get("value");
            if (!(val instanceof List pages)) return searchHtml(query, count);

            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (Object p : pages) {
                if (++n > count) break;
                if (!(p instanceof Map page)) continue;
                sb.append(n).append(". **").append(page.getOrDefault("name", "")).append("**\n")
                        .append("   ").append(page.getOrDefault("url", "")).append("\n")
                        .append("   ").append(page.getOrDefault("snippet", "")).append("\n\n");
            }
            return n > 0 ? ToolResult.ok(sb.toString())
                    : ToolResult.ok("未找到结果");
        } catch (Exception e) {
            return searchHtml(query, count);
        }
    }

    /** HTML 抓取（备胎） */
    private ToolResult searchHtml(String query, int count) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(8)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BING_HTML + "?q=" + encoded))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(Duration.ofSeconds(8)).GET().build();
            String html = client.send(req, HttpResponse.BodyHandlers.ofString()).body();

            StringBuilder sb = new StringBuilder();
            int found = 0;
            for (String block : html.split("<li class=\"b_algo\"")) {
                if (found >= count) break;
                String title = extractTag(block, "h2");
                String cite = extractTag(block, "cite");
                if (title == null) continue;
                title = title.replaceAll("<[^>]+>", "").trim();
                if (title.isEmpty()) continue;
                String url = cite != null ? cite.replaceAll("<[^>]+>", "").trim() : "";
                if (url != null && !url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://" + url;
                }
                sb.append(++found).append(". **").append(title).append("**\n   ").append(url).append("\n\n");
            }
            return found > 0 ? ToolResult.ok(sb.toString())
                    : ToolResult.ok("未找到结果。换关键词试试。");
        } catch (Exception e) {
            return ToolResult.error("搜索失败: " + e.getMessage());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<Map<String, Object>> uncheckedCast(List l) { return l; }

    /** 取第一个非空白值（配置注入优先，其次环境变量），都空返回 null。 */
    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return null;
    }

    /** 提取指定标签内容，兼容带属性的标签（如 {@code <h2 class="">}）。 */
    private static String extractTag(String s, String tag) {
        Matcher m = Pattern.compile("<" + tag + "[^>]*>(.*?)</" + tag + ">",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(s);
        return m.find() ? m.group(1) : null;
    }
}
