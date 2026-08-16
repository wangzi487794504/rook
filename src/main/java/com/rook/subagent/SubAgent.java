package com.rook.subagent;

import com.rook.llm.*;
import com.rook.tools.*;
import com.rook.worktree.WorktreeManager;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 子Agent执行引擎 —— RunToCompletion模式，结果异步返回。
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class SubAgent {

    /**
     * 执行子Agent并返回结果。
     * @param mode "defined" | "fork"
     * @param agentType 角色名(defined时必填)
     * @param task 任务描述
     * @param parentHistory 父对话历史(fork时用)
     * @param provider LLM客户端(共享)
     * @param tools 完整工具注册表(共享)
     * @param globalDisallowed 全局禁止工具列表
     */
    public static CompletableFuture<SubAgentResult> run(
            String mode, String agentType, String task,
            List<ChatMessage> parentHistory, LlmProvider provider,
            ToolRegistry tools, List<String> globalDisallowed,
            AgentDefLoader loader, Path userDir, Path projectDir,
            Path repoRoot) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                List<ChatMessage> history = new ArrayList<>();
                Path worktreePath = null;
                WorktreeManager wtManager = new WorktreeManager(repoRoot);

                if ("fork".equals(mode)) {
                    history.addAll(parentHistory);
                    history.add(new ChatMessage("user", "[Fork Agent] " + task +
                            "\n你在一个独立的子对话中运行。完成后给出最终回复。"));
                } else {
                    Map<String, AgentDef> defs = loader != null
                            ? loader.loadAll(userDir, projectDir)
                            : Collections.emptyMap();
                    AgentDef def = defs.get(agentType);
                    if (def == null) {
                        return new SubAgentResult(false, "角色不存在: " + agentType, null,
                                0, 0, "defined");
                    }

                    // ★ G12: Worktree 隔离
                    String wtNotice = "";
                    if ("worktree".equals(def.isolation())) {
                        try {
                            String wtSlug = "agent-" + agentType;
                            worktreePath = wtManager.create(wtSlug);
                            wtNotice = "\n\n[工作目录: " + worktreePath + "]\n"
                                    + "所有文件操作请在此目录下进行，不影响主项目。";
                        } catch (Exception e) {
                            wtNotice = "\n\n[Worktree 创建失败: " + e.getMessage()
                                    + "，降级为普通模式]";
                        }
                    }
                    history.add(new ChatMessage("system", def.prompt() + wtNotice));
                    history.add(new ChatMessage("user", task));
                }

                String result = provider.complete(history);
                Usage u = provider.lastUsage();

                // ★ G9: 自动清理
                String cleanupMsg = "";
                if (worktreePath != null) {
                    cleanupMsg = wtManager.autoCleanup("agent-" + agentType);
                    if (cleanupMsg != null) result += "\n\n" + cleanupMsg;
                }

                return new SubAgentResult(true, null, result,
                        u != null ? u.inputTokens() : 0,
                        u != null ? u.outputTokens() : 0,
                        mode);
            } catch (Exception e) {
                return new SubAgentResult(false, "执行失败: " + e.getMessage(), null, 0, 0, mode);
            }
        });
    }

    public record SubAgentResult(boolean success, String error, String output,
                                  int inputTokens, int outputTokens, String mode) {}
}
