package com.mewcode.config;

import java.util.List;

/**
 * 全局应用配置，包含所有 Provider 配置和默认 Provider。
 *
 * @author MewCode
 * @since 0.1.0
 */
public record AppConfig(
        List<ProviderConfig> providers,
        String defaultProvider) {
}
