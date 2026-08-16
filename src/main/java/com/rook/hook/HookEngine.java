package com.rook.hook;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Hook 引擎 —— 两层配置叠加 + 同名冲突检测 + 事件分发 + 拦截消费。
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class HookEngine {

    private final List<HookRule> rules;
    private final Set<String> firedOnce = Collections.synchronizedSet(new HashSet<>());

    /** @return 方法返回值 */
    public HookEngine(Path projectConfig, Path userConfig) {
        List<HookRule> all = new ArrayList<>();
        // 用户级先加载（低优先），项目级后加载（高优先）
        load(userConfig, all);
        load(projectConfig, all);
        this.rules = List.copyOf(all);
    }

    @SuppressWarnings("unchecked")
    private void load(Path file, List<HookRule> out) {
        if (file == null || !Files.exists(file)) return;
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(Files.newInputStream(file));
            if (root == null) return;
            List<Map<String, Object>> hookList = (List<Map<String, Object>>) root.get("hooks");
            if (hookList == null) return;

            for (var m : hookList) {
                try {
                    HookRule rule = HookRule.fromMap(m, file.toString());
                    // F7: 同名冲突检测
                    boolean conflict = out.stream().anyMatch(r -> r.name().equals(rule.name()));
                    if (conflict) {
                        System.err.println("[Hook] 同名冲突，跳过后者: " + rule.name()
                                + " (来源: " + file + ")");
                        continue;
                    }
                    out.add(rule);
                } catch (Exception e) {
                    System.err.println("[Hook] 规则加载失败: " + e.getMessage()
                            + " (来源: " + file + ")");
                }
            }
        } catch (IOException e) {
            System.err.println("[Hook] 配置读取失败: " + file + " - " + e.getMessage());
        }
    }

    /**
     * 触发事件。返回拦截原因（pre_tool 被拦截时），否则 null。
     */
    /** @return 方法返回值 */
    public String fire(HookEvent event, HookPayload payload) {
        HookContext ctx = new HookContext(event, payload);
        String intercept = null;

        for (HookRule rule : rules) {
            if (rule.event() != event) continue;

            HookAction action = rule.action();

            // only_once 检查
            if (rule.onlyOnce()) {
                if (!firedOnce.add(rule.name())) continue;
            }

            // async 动作后台执行
            if (rule.async()) {
                new Thread(() -> executeAction(rule, ctx, payload)).start();
                continue;
            }

            // 条件检查
            if (rule.condition() != null && !rule.condition().evaluate(ctx)) continue;

            // 同步执行
            HookResult result = executeAction(rule, ctx, payload);
            if (result != null && result.intercept()) {
                intercept = result.message();
            }
        }
        return intercept;
    }

    /** 执行单条动作，返回结果 */
    private HookResult executeAction(HookRule rule, HookContext ctx, HookPayload payload) {
        try {
            HookAction action = rule.action();
            HookResult result;
            if (action instanceof HookAction.Command cmd) {
                result = cmd.execute(ctx, rule.timeoutSec());
            } else if (action instanceof HookAction.Prompt prompt) {
                result = prompt.execute(ctx);
            } else if (action instanceof HookAction.HttpRequest http) {
                result = http.execute(ctx, rule.timeoutSec());
            } else if (action instanceof HookAction.SubAgent sub) {
                result = sub.execute(ctx);
            } else {
                return HookResult.ok("");
            }
            if (!result.ok()) {
                System.err.println("[Hook:" + rule.name() + "] 执行失败: " + result.message());
            }
            return result;
        } catch (Exception e) {
            System.err.println("[Hook:" + rule.name() + "] 异常: " + e.getMessage());
            return HookResult.error(e.getMessage());
        }
    }

    /** 触发事件并收集所有 prompt 动作的注入文本 */
    /** @return 方法返回值 */
    public List<String> fireAndCollect(HookEvent event, HookPayload payload) {
        HookContext ctx = new HookContext(event, payload);
        List<String> injections = new ArrayList<>();
        for (HookRule rule : rules) {
            if (rule.event() != event) continue;
            if (rule.condition() != null && !rule.condition().evaluate(ctx)) continue;
            if (rule.action() instanceof HookAction.Prompt p) {
                injections.add(p.text());
            }
        }
        return injections;
    }
}
