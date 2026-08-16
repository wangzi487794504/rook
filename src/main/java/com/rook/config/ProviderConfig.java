package com.rook.config;

/**
 * 单个 LLM Provider 的配置信息，对应 config.yaml 中 providers 数组的一条。
 *
 * @param name    Provider 名称，如 "deepseek"，用于 :switch 切换
 * @param protocol 协议类型：anthropic 或 openai
 * @param model   模型名称，如 "deepseek-v4-pro"
 * @param baseUrl API 端点地址，如 https://api.deepseek.com/anthropic
 * @param apiKey  认证密钥，支持 ${ENV_VAR} 环境变量引用
 * @author wangzijie
 * @since 0.1.0
 */
public record ProviderConfig(
        String name,
        String protocol,
        String model,
        String baseUrl,
        String apiKey) {
}
