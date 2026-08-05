package com.mewcode.llm;

import com.mewcode.config.AppConfig;
import com.mewcode.config.ProviderConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provider 工厂，根据配置中的 protocol 字段创建对应的 LlmProvider 实例。
 *
 * @author MewCode
 * @since 0.1.0
 */
public class ProviderFactory {

    /**
     * 根据 AppConfig 创建所有 Provider 实例。
     *
     * @param config 全局配置
     * @return 以 name 为 key 的 Provider Map
     * @throws IllegalArgumentException 当 protocol 不识别时
     */
    public static Map<String, LlmProvider> create(AppConfig config) {
        Map<String, LlmProvider> providers = new LinkedHashMap<>();

        for (ProviderConfig pc : config.providers()) {
            LlmProvider provider = switch (pc.protocol().toLowerCase()) {
                case "anthropic" -> new AnthropicProvider(pc);
                case "openai" -> new OpenAiProvider(pc);
                default -> throw new IllegalArgumentException(
                        "不支持的协议类型: " + pc.protocol() + " (provider: " + pc.name() + ")");
            };
            providers.put(pc.name(), provider);
        }

        return providers;
    }
}
