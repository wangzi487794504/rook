package com.rook.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConfigLoader 单元测试。
 *
 * @author wangzijie
 * @since 0.1.0
 */
class ConfigLoaderTest {

    @Test
    void should_加载正常配置_When_文件格式正确(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
                default_provider: myai
                providers:
                  - name: myai
                    protocol: anthropic
                    model: claude-3
                    base_url: https://api.example.com
                    api_key: sk-test123
                """);

        AppConfig config = ConfigLoader.load(configFile);

        assertEquals("myai", config.defaultProvider());
        assertEquals(1, config.providers().size());
        ProviderConfig pc = config.providers().get(0);
        assertEquals("myai", pc.name());
        assertEquals("anthropic", pc.protocol());
        assertEquals("claude-3", pc.model());
        assertEquals("https://api.example.com", pc.baseUrl());
        assertEquals("sk-test123", pc.apiKey());
    }

    @Test
    void should_加载多Provider配置_When_多个Provider定义(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
                default_provider: gpt
                providers:
                  - name: deepseek
                    protocol: anthropic
                    model: deepseek-v4
                    base_url: https://api.deepseek.com/anthropic
                    api_key: sk-aaa
                  - name: gpt
                    protocol: openai
                    model: gpt-4o
                    base_url: https://api.openai.com/v1
                    api_key: sk-bbb
                """);

        AppConfig config = ConfigLoader.load(configFile);

        assertEquals(2, config.providers().size());
        assertEquals("anthropic", config.providers().get(0).protocol());
        assertEquals("openai", config.providers().get(1).protocol());
    }

    @Test
    void should_抛出异常_When_文件不存在() {
        Path nonExistent = Path.of("/nonexistent/config.yaml");
        assertThrows(IOException.class, () -> ConfigLoader.load(nonExistent));
    }

    @Test
    void should_抛出异常_When_缺少必填字段(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
                default_provider: myai
                providers:
                  - name: myai
                    protocol: anthropic
                    model: claude-3
                    base_url: https://api.example.com
                """);

        assertThrows(IllegalStateException.class, () -> ConfigLoader.load(configFile));
    }

    @Test
    void should_抛出异常_When_protocol不合法(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
                default_provider: myai
                providers:
                  - name: myai
                    protocol: unknown
                    model: claude-3
                    base_url: https://api.example.com
                    api_key: sk-test
                """);

        assertThrows(IllegalStateException.class, () -> ConfigLoader.load(configFile));
    }

    @Test
    void should_抛出异常_When_defaultProvider不存在(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
                default_provider: nonexistent
                providers:
                  - name: myai
                    protocol: anthropic
                    model: claude-3
                    base_url: https://api.example.com
                    api_key: sk-test
                """);

        assertThrows(IllegalStateException.class, () -> ConfigLoader.load(configFile));
    }
}
