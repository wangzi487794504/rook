package com.rook.mcp;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP 配置加载 —— 两层 YAML 合并 + ${VAR} 环境变量展开。
 *
 * @author wangzijie
 * @since 0.6.0
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class McpConfig {

    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{(\\w+)(?::-(\\w*))?\\}");

    /** 从用户级和项目级配置中加载 Server 列表，项目级覆盖用户级同名 Server */
    @SuppressWarnings("unchecked")
    public static List<McpServerConfig> load(Path userConfig, Path projectConfig) {
        Map<String, McpServerConfig> merged = new LinkedHashMap<>();

        // 用户级（低优先级）
        loadFile(userConfig).forEach(s -> merged.put(s.name(), s));
        // 项目级（高优先级，覆盖）
        loadFile(projectConfig).forEach(s -> merged.put(s.name(), s));

        return new ArrayList<>(merged.values());
    }

    @SuppressWarnings("unchecked")
    private static List<McpServerConfig> loadFile(Path file) {
        if (file == null || !Files.exists(file)) return List.of();
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(Files.newInputStream(file));
            if (root == null) return List.of();
            Map<String, Map<String, Object>> servers = (Map<String, Map<String, Object>>) root.get("servers");
            if (servers == null) return List.of();

            return servers.entrySet().stream()
                    .map(e -> parseServer(e.getKey(), e.getValue()))
                    .toList();
        } catch (IOException e) {
            System.err.println("[MCP] 配置加载失败: " + file + " - " + e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static McpServerConfig parseServer(String name, Map<String, Object> map) {
        String type = String.valueOf(map.getOrDefault("type", "stdio"));
        String command = (String) map.get("command");
        List<String> args = (List<String>) map.getOrDefault("args", List.of());
        Map<String, String> env = (Map<String, String>) map.getOrDefault("env", Map.of());
        String url = (String) map.get("url");
        Map<String, String> headers = (Map<String, String>) map.getOrDefault("headers", Map.of());

        // 展开环境变量
        command = expand(command);
        args = args.stream().map(McpConfig::expand).toList();
        Map<String, String> expandedEnv = new LinkedHashMap<>();
        env.forEach((k, v) -> expandedEnv.put(k, expand(v)));
        url = expand(url);
        Map<String, String> expandedHeaders = new LinkedHashMap<>();
        headers.forEach((k, v) -> expandedHeaders.put(k, expand(v)));

        return new McpServerConfig(name, type, command, args, expandedEnv, url, expandedHeaders);
    }

    static String expand(String value) {
        if (value == null) return null;
        Matcher m = VAR_PATTERN.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String varName = m.group(1);
            String defaultValue = m.group(2);
            String envValue = System.getenv(varName);
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    envValue != null ? envValue : (defaultValue != null ? defaultValue : m.group(0))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public record McpServerConfig(String name, String type, String command, List<String> args,
                                   Map<String, String> env, String url, Map<String, String> headers) {}
}
