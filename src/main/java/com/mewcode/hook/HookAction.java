package com.mewcode.hook;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Hook 动作类型 */
public sealed interface HookAction {

    HookResult execute(HookContext ctx);

    /** Shell 命令。退出码 2 = 拦截，stderr = 拒绝原因。 */
    record Command(String command) implements HookAction {
        public HookResult execute(HookContext ctx) { return execute(ctx, 30); }
        public HookResult execute(HookContext ctx, int timeout) {
            try {
                boolean win = System.getProperty("os.name").toLowerCase().contains("win");
                ProcessBuilder pb = new ProcessBuilder(
                        win ? new String[]{"cmd", "/c", command}
                            : new String[]{"sh", "-c", command});
                Process p = pb.start();
                boolean finished = p.waitFor(timeout, TimeUnit.SECONDS);
                if (!finished) { p.destroyForcibly(); return HookResult.error("超时"); }
                String out = new String(p.getInputStream().readAllBytes());
                String err = new String(p.getErrorStream().readAllBytes());
                if (p.exitValue() == 2) {
                    return HookResult.intercept(err.isBlank() ? out : err);
                }
                return HookResult.ok(out);
            } catch (Exception e) {
                return HookResult.error(e.getMessage());
            }
        }
    }

    /** 提示词注入 */
    record Prompt(String text, String position) implements HookAction {
        @Override public HookResult execute(HookContext ctx) {
            return HookResult.inject(text);
        }
    }

    /** HTTP 请求 */
    record HttpRequest(String url, String method, Map<String,String> headers, String body) implements HookAction {
        @Override public HookResult execute(HookContext ctx) { return execute(ctx, 30); }
        public HookResult execute(HookContext ctx, int timeout) {
            try {
                var req = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(url))
                        .timeout(java.time.Duration.ofSeconds(timeout))
                        .method(method, body != null && !body.isEmpty()
                                ? java.net.http.HttpRequest.BodyPublishers.ofString(body)
                                : java.net.http.HttpRequest.BodyPublishers.noBody());
                headers.forEach(req::header);
                var resp = java.net.http.HttpClient.newHttpClient()
                        .send(req.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                return resp.statusCode() < 400 ? HookResult.ok(resp.body())
                        : HookResult.error("HTTP " + resp.statusCode());
            } catch (Exception e) {
                return HookResult.error(e.getMessage());
            }
        }
    }

    /** 子 Agent（占位） */
    record SubAgent(String name, String prompt) implements HookAction {
        @Override public HookResult execute(HookContext ctx) {
            return HookResult.ok("[SubAgent:" + name + "] 占位未实现");
        }
    }
}
