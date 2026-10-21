package com.rook.config;

/**
 * 联网搜索 API 配置（可选），对应 config.yaml 中的 search 节。
 * <p>
 * 未配置（{@code null} 或 key 为空）时，WebSearchTool 回退到 Bing HTML 抓取。
 *
 * @param glmApiKey   智谱 GLM Web Search API key，可为 null/空
 * @param braveApiKey Brave Search API key，可为 null/空
 * @author wangzijie
 * @since 0.1.0
 */
public record SearchConfig(
        String glmApiKey,
        String braveApiKey) {
}
