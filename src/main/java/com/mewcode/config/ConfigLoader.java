package com.mewcode.config;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * YAML 配置文件加载器，负责读取和校验配置文件。
 *
 * @author MewCode
 * @since 0.1.0
 */
public class ConfigLoader {

    private static final List<String> VALID_PROTOCOLS = List.of("anthropic", "openai");

    /**
     * 从指定路径加载并校验配置文件。
     *
     * @param path 配置文件路径
     * @return 校验通过的 AppConfig
     * @throws IOException         文件不存在或读取失败
     * @throws YAMLException       YAML 格式错误
     * @throws IllegalStateException 校验失败
     */
    @SuppressWarnings("unchecked")
    public static AppConfig load(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("配置文件不存在: " + path.toAbsolutePath());
        }

        Yaml yaml = new Yaml();
        Map<String, Object> root;
        try {
            root = yaml.load(Files.newInputStream(path));
        } catch (YAMLException e) {
            throw new IOException("配置文件 YAML 格式错误: " + e.getMessage(), e);
        }

        if (root == null) {
            throw new IOException("配置文件为空");
        }

        // 解析 default_provider
        String defaultProvider = (String) root.get("default_provider");
        if (defaultProvider == null || defaultProvider.isBlank()) {
            throw new IllegalStateException("缺少 default_provider 字段");
        }

        // 解析 providers 列表
        List<Map<String, Object>> providerMaps = (List<Map<String, Object>>) root.get("providers");
        if (providerMaps == null || providerMaps.isEmpty()) {
            throw new IllegalStateException("providers 列表不能为空");
        }

        List<ProviderConfig> providers = providerMaps.stream()
                .map(ConfigLoader::parseProvider)
                .toList();

        // 校验 defaultProvider 存在于 providers 中
        boolean defaultExists = providers.stream()
                .anyMatch(p -> p.name().equals(defaultProvider));
        if (!defaultExists) {
            throw new IllegalStateException(
                    "default_provider '" + defaultProvider + "' 不在 providers 列表中，"
                    + "可用: " + providers.stream().map(ProviderConfig::name).toList());
        }

        return new AppConfig(providers, defaultProvider);
    }

    /**
     * 解析单个 Provider 配置，校验各字段。
     */
    private static ProviderConfig parseProvider(Map<String, Object> map) {
        String name = requireField(map, "name");
        String protocol = requireField(map, "protocol").toLowerCase();
        String model = requireField(map, "model");
        String baseUrl = requireField(map, "base_url");
        String apiKey = requireField(map, "api_key");

        if (!VALID_PROTOCOLS.contains(protocol)) {
            throw new IllegalStateException(
                    "Provider '" + name + "' 的 protocol 不合法: " + protocol
                    + "，支持的值: " + VALID_PROTOCOLS);
        }

        return new ProviderConfig(name, protocol, model, baseUrl, apiKey);
    }

    /**
     * 从 Map 中获取必填字段，字段缺失或为空时抛出异常。
     */
    private static String requireField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null || value.toString().isBlank()) {
            Object nameObj = map.getOrDefault("name", "未知");
            String name = nameObj != null ? nameObj.toString() : "未知";
            throw new IllegalStateException(
                    "Provider '" + name + "' 缺少必填字段: " + key);
        }
        return value.toString().trim();
    }
}
