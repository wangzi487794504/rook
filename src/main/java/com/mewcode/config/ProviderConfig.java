package com.mewcode.config;

/**
 * 单个 LLM Provider 的配置信息。
 *
 * @author MewCode
 * @since 0.1.0
 */
public record ProviderConfig(
        String name,
        String protocol,
        String model,
        String baseUrl,
        String apiKey) {
}
