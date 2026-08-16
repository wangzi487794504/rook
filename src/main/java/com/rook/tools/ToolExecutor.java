package com.rook.tools;

import com.rook.hook.*;
import com.rook.permission.PermissionEngine;
import com.rook.permission.PermissionResult;
import org.jline.reader.LineReader;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.*;

/**
 * 保序分批并发执行器 —— 连续的只读工具并发执行，写入工具串行执行。
 * 执行前经过权限引擎五层检查。
 *
 * @author wangzijie
 * @since 0.3.0
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class ToolExecutor {

    private static final Set<String> READ_ONLY_TOOLS = Set.of("read_file", "glob", "grep");

    private final ToolRegistry registry;
    private final int maxParallelReads;
    private final PermissionEngine permissionEngine;
    private final HookEngine hookEngine;
    private LineReader lineReader;

    /** @return 方法返回值 */
    public ToolExecutor(ToolRegistry registry, int maxParallelReads,
                        PermissionEngine permissionEngine, HookEngine hookEngine) {
        this.registry = registry;
        this.maxParallelReads = maxParallelReads;
        this.permissionEngine = permissionEngine;
        this.hookEngine = hookEngine;
    }

    /** 设置 LineReader 用于人在回路交互 */
    public void setLineReader(LineReader reader) { this.lineReader = reader; }

    /** 获取权限引擎，供外部切换模式 */
    /** @return 方法返回值 */
    public PermissionEngine getPermissionEngine() { return permissionEngine; }

    /**
     * 保序分批并发执行工具调用列表。
     *
     * @param calls 模型请求的工具调用列表
     * @return 结果列表，顺序与输入一致
     */
    /** @return 方法返回值 */
    public List<ToolResult> execute(List<ToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }

        // 结果 map（index → result），保持顺序
        Map<Integer, ToolResult> resultMap = new ConcurrentHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(maxParallelReads);

        try {
            int i = 0;
            while (i < calls.size()) {
                ToolCall call = calls.get(i);

                if (isReadOnly(call)) {
                    // 收集连续的只读调用
                    List<Integer> batchIndices = new ArrayList<>();
                    List<Callable<Void>> batch = new ArrayList<>();

                    while (i < calls.size() && isReadOnly(calls.get(i))) {
                        int idx = i;
                        batchIndices.add(idx);
                        batch.add(() -> {
                            resultMap.put(idx, executeOne(calls.get(idx)));
                            return null;
                        });
                        i++;
                    }

                    // 并发执行整批
                    try {
                        executor.invokeAll(batch);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        for (int idx : batchIndices) {
                            resultMap.putIfAbsent(idx,
                                    ToolResult.error("执行被中断"));
                        }
                    }
                } else {
                    // 有副作用工具 —— 单独串行执行
                    resultMap.put(i, executeOne(call));
                    i++;
                }
            }
        } finally {
            executor.shutdownNow();
        }

        // 按原始顺序排列
        List<ToolResult> results = new ArrayList<>();
        for (int j = 0; j < calls.size(); j++) {
            results.add(resultMap.getOrDefault(j,
                    ToolResult.error("工具未执行")));
        }
        return results;
    }

    private ToolResult executeOne(ToolCall call) {
        Optional<Tool> tool = registry.get(call.name());
        if (tool.isEmpty()) {
            return ToolResult.error("未知工具: " + call.name());
        }

        // ★ Hook: PRE_TOOL_USE（在权限检查之前）
        if (hookEngine != null) {
            String interceptReason = hookEngine.fire(HookEvent.PRE_TOOL_USE,
                    HookPayload.forTool(HookEvent.PRE_TOOL_USE, call, null, false));
            if (interceptReason != null) {
                ToolResult denied = ToolResult.error("HOOK_INTERCEPT: " + interceptReason);
                // POST_TOOL_USE 仍然触发
                if (hookEngine != null) hookEngine.fire(HookEvent.POST_TOOL_USE,
                        HookPayload.forTool(HookEvent.POST_TOOL_USE, call, denied, true));
                return denied;
            }
        }

        // ★ 权限检查
        PermissionResult perm = permissionEngine.check(call, lineReader);
        if (perm.isDenied()) {
            ToolResult denied = ToolResult.error("PERMISSION_DENIED: " + perm.reason());
            if (hookEngine != null) hookEngine.fire(HookEvent.POST_TOOL_USE,
                    HookPayload.forTool(HookEvent.POST_TOOL_USE, call, denied, true));
            return denied;
        }

        ToolResult result;
        try {
            result = tool.get().execute(call.input());
        } catch (Exception e) {
            result = ToolResult.error("工具执行异常: " + e.getMessage());
        }

        // ★ Hook: POST_TOOL_USE
        if (hookEngine != null) hookEngine.fire(HookEvent.POST_TOOL_USE,
                HookPayload.forTool(HookEvent.POST_TOOL_USE, call, result, false));
        return result;
    }

    private static boolean isReadOnly(ToolCall call) {
        return READ_ONLY_TOOLS.contains(call.name());
    }
}
