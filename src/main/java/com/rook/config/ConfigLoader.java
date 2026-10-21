package com.rook.config;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YAML 配置文件加载器 —— 读取 config.yaml，校验字段完整性，展开 ${ENV_VAR} 环境变量。
 * <p>
 * 配置格式：
 * <pre>
 * default_provider: deepseek
 * providers:
 *   - name: deepseek
 *     protocol: anthropic
 *     model: deepseek-v4-pro
 *     base_url: https://api.deepseek.com/anthropic
 *     api_key: ${DEEPSEEK_API_KEY}        ← 支持环境变量引用
 * </pre>
 *
 * @author wangzijie
 * @since 0.1.0
 */
public class ConfigLoader {

    /** 合法的 protocol 值 */
    private static final List<String> VALID_PROTOCOLS = List.of("anthropic", "openai");
    /** 匹配 ${VAR} 和 ${VAR:默认值} */
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{(\\w+)(?::-(\\w*))?\\}");

    /**
     * 从指定路径加载并校验配置文件。
     * <p>
     * 校验包括：文件存在、YAML 格式正确、providers 非空、
     * 四个必填字段完整、protocol 合法、defaultProvider 在列表中。
     *
     * @param path 配置文件路径
     * @return 校验通过的 AppConfig
     * @throws IOException             文件不存在或读取失败
     * @throws IllegalStateException   字段缺失/不合法
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

        if (root == null) throw new IOException("配置文件为空");

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
                .map(ConfigLoader::parseProvider).toList();

        // 校验 defaultProvider 存在于 providers 中
        boolean defaultExists = providers.stream().anyMatch(p -> p.name().equals(defaultProvider));
        if (!defaultExists) {
            throw new IllegalStateException(
                    "default_provider '" + defaultProvider + "' 不在列表中，"
                    + "可用: " + providers.stream().map(ProviderConfig::name).toList());
        }

        return new AppConfig(providers, defaultProvider, parseSearch(root.get("search")));
    }

    /** 解析可选 search 节；未配置或 key 全空返回 null。 */
    private static SearchConfig parseSearch(Object obj) {
        if (!(obj instanceof Map<?, ?> map)) return null;
        String glm = optional(map, "glm_api_key");
        String brave = optional(map, "brave_api_key");
        return (glm == null && brave == null) ? null : new SearchConfig(glm, brave);
    }

    /** 取可选字段；缺失/展开后为空返回 null，值自动展开 ${VAR}。 */
    private static String optional(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        String expanded = expand(value.toString().trim());
        return expanded.isBlank() ? null : expanded;
    }

    /** 解析单个 Provider，提取并校验五个字段 */
    private static ProviderConfig parseProvider(Map<String, Object> map) {
        String name = requireField(map, "name");
        String protocol = requireField(map, "protocol").toLowerCase();
        String model = requireField(map, "model");
        String baseUrl = requireField(map, "base_url");
        String apiKey = requireField(map, "api_key");

        if (!VALID_PROTOCOLS.contains(protocol)) {
            throw new IllegalStateException("Provider '" + name
                    + "' 的 protocol 不合法: " + protocol);
        }

        return new ProviderConfig(name, protocol, model, baseUrl, apiKey);
    }

    /** 从 Map 取必填字段，缺失/空白抛异常。值自动展开 ${VAR} */
    private static String requireField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null || value.toString().isBlank()) {
            Object nameObj = map.getOrDefault("name", "未知");
            String name = nameObj != null ? nameObj.toString() : "未知";
            throw new IllegalStateException("Provider '" + name + "' 缺少必填字段: " + key);
        }
        return expand(value.toString().trim());
    }

    /**
     * 展开字符串中的 ${ENV_VAR} 引用。
     * 支持 ${VAR}（未设时报错）和 ${VAR:默认值}（未设时用默认值）。
     */
    static String expand(String value) {
        if (value == null || !value.contains("${")) return value;
        Matcher m = VAR_PATTERN.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String envValue = System.getenv(m.group(1));
            String replacement = envValue != null ? envValue
                    : (m.group(2) != null ? m.group(2) : m.group(0));
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
