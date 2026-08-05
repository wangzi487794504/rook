package com.mewcode.llm;

import com.mewcode.config.AppConfig;
import com.mewcode.config.ProviderConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProviderFactory 单元测试。
 *
 * @author MewCode
 * @since 0.1.0
 */
class ProviderFactoryTest {

    @Test
    void should_返回AnthropicProvider_When_protocol为anthropic() {
        AppConfig config = new AppConfig(
                List.of(new ProviderConfig("test", "anthropic", "claude-3",
                        "https://api.example.com", "sk-test")),
                "test");

        Map<String, LlmProvider> providers = ProviderFactory.create(config);

        assertEquals(1, providers.size());
        assertTrue(providers.get("test") instanceof AnthropicProvider);
        assertEquals("test", providers.get("test").name());
    }

    @Test
    void should_返回OpenAiProvider_When_protocol为openai() {
        AppConfig config = new AppConfig(
                List.of(new ProviderConfig("gpt", "openai", "gpt-4o",
                        "https://api.openai.com/v1", "sk-test")),
                "gpt");

        Map<String, LlmProvider> providers = ProviderFactory.create(config);

        assertEquals(1, providers.size());
        assertTrue(providers.get("gpt") instanceof OpenAiProvider);
        assertEquals("gpt", providers.get("gpt").name());
    }

    @Test
    void should_抛出异常_When_protocol不合法() {
        AppConfig config = new AppConfig(
                List.of(new ProviderConfig("bad", "unknown", "model",
                        "https://api.example.com", "sk-test")),
                "bad");

        assertThrows(IllegalArgumentException.class, () -> ProviderFactory.create(config));
    }

    @Test
    void should_创建多个Provider_When_配置多个后端() {
        AppConfig config = new AppConfig(
                List.of(
                        new ProviderConfig("ds", "anthropic", "deepseek-v4",
                                "https://api.deepseek.com/anthropic", "sk-aaa"),
                        new ProviderConfig("gpt", "openai", "gpt-4o",
                                "https://api.openai.com/v1", "sk-bbb")),
                "ds");

        Map<String, LlmProvider> providers = ProviderFactory.create(config);

        assertEquals(2, providers.size());
        assertTrue(providers.containsKey("ds"));
        assertTrue(providers.containsKey("gpt"));
        assertTrue(providers.get("ds") instanceof AnthropicProvider);
        assertTrue(providers.get("gpt") instanceof OpenAiProvider);
    }
}
