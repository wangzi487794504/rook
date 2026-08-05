package com.mewcode.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JSON-RPC 2.0 消息构造和解析工具。
 *
 * @author MewCode
 * @since 0.6.0
 */
public class JsonRpc {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger ID_GEN = new AtomicInteger(1);

    /** 构造 JSON-RPC 请求字符串 */
    public static String buildRequest(int id, String method, Map<String, Object> params) {
        try {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("jsonrpc", "2.0");
            req.put("id", id);
            req.put("method", method);
            req.put("params", params != null ? params : Map.of());
            return MAPPER.writeValueAsString(req);
        } catch (Exception e) {
            throw new RuntimeException("JSON-RPC 序列化失败", e);
        }
    }

    public static int nextId() { return ID_GEN.getAndIncrement(); }

    /** 解析 JSON-RPC 响应 */
    @SuppressWarnings("unchecked")
    public static JsonRpcResponse parseResponse(String json) {
        try {
            Map<String, Object> map = MAPPER.readValue(json, Map.class);
            if (map.containsKey("error") && map.get("error") != null) {
                Map<String, Object> err = (Map<String, Object>) map.get("error");
                return JsonRpcResponse.error(
                        ((Number) err.getOrDefault("code", -1)).intValue(),
                        String.valueOf(err.getOrDefault("message", "unknown")));
            }
            return JsonRpcResponse.ok(map.get("result"));
        } catch (Exception e) {
            return JsonRpcResponse.error(-32700, "JSON 解析失败: " + e.getMessage());
        }
    }

    public record JsonRpcResponse(boolean success, Object result, int errorCode, String errorMessage) {
        public static JsonRpcResponse ok(Object result) {
            return new JsonRpcResponse(true, result, 0, null);
        }
        public static JsonRpcResponse error(int code, String msg) {
            return new JsonRpcResponse(false, null, code, msg);
        }
    }
}
