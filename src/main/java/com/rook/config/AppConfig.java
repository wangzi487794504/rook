package com.rook.config;

import java.util.List;

/**
 * 全局应用配置，对应 config.yaml 的完整解析结果。
 *
 * @param providers       所有 Provider 配置列表
 * @param defaultProvider 默认使用的 Provider 名称，必须在 providers 中存在
 * @param search          联网搜索配置（可选，未配置时为 null）
 * @author wangzijie
 * @since 0.1.0
 */
public record AppConfig(
        List<ProviderConfig> providers,
        String defaultProvider,
        SearchConfig search) {

    /** 兼容旧的两参构造（无搜索配置），search 置空。 */
    public AppConfig(List<ProviderConfig> providers, String defaultProvider) {
        this(providers, defaultProvider, null);
    }
}
