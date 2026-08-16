package com.rook.cli;

import com.rook.cmd.*;
import com.rook.hook.HookEngine;
import com.rook.hook.HookEvent;
import com.rook.hook.HookPayload;
import com.rook.team.Team;
import com.rook.team.TeamManager;
import com.rook.worktree.WorktreeManager;
import com.rook.core.agent.AgentEvent;
import com.rook.core.agent.AgentEventType;
import com.rook.core.agent.AgentLoop;
import com.rook.core.agent.LoopConfig;
import com.rook.llm.*;
import com.rook.mcp.McpManager;
import com.rook.permission.PermissionEngine;
import com.rook.prompt.PromptBuilder;
import com.rook.session.Session;
import com.rook.tools.ToolRegistry;
import com.rook.tools.ToolResult;
import org.jline.reader.*;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * @author wangzijie
 * @since 0.1.0
 */
public class Repl implements Console {

    private static final Set<String> READ_ONLY_TOOLS = Set.of("read_file", "glob", "grep");

    private final Map<String, LlmProvider> providers;
    private final Map<String, String> providerModels;
    private LlmProvider currentProvider;
    private String currentModel;
    private final Session session;
    private final ToolRegistry toolRegistry;
    private final AgentLoop agentLoop;
    private final PermissionEngine permissionEngine;
    private final McpManager mcpManager;
    private final com.rook.context.ContextManager contextManager;
    private final com.rook.memory.AutoMemory autoMemory;
    private final CommandDispatcher dispatcher;
    private final CommandRegistry cmdRegistry;
    private final HookEngine hookEngine;
    private final WorktreeManager wtManager;
    private final TeamManager teamManager;
    private LineReader lineReader;
    private boolean planMode;

    /** @return 方法返回值 */
    public Repl(Map<String, LlmProvider> providers, Map<String, String> providerModels,
                LlmProvider defaultProvider, String defaultModel,
                Session session, ToolRegistry toolRegistry,
                LoopConfig loopConfig, PromptBuilder promptBuilder,
                PermissionEngine permissionEngine, McpManager mcpManager,
                com.rook.context.ContextManager contextManager,
                com.rook.memory.AutoMemory autoMemory,
                CommandRegistry cmdRegistry, HookEngine hookEngine,
                WorktreeManager wtManager, TeamManager teamManager) {
        this.providers = providers;
        this.providerModels = providerModels;
        this.currentProvider = defaultProvider;
        this.currentModel = defaultModel;
        this.session = session;
        this.toolRegistry = toolRegistry;
        this.permissionEngine = permissionEngine;
        this.mcpManager = mcpManager;
        this.contextManager = contextManager;
        this.autoMemory = autoMemory;
        this.hookEngine = hookEngine;
        this.wtManager = wtManager;
        this.teamManager = teamManager;
        this.agentLoop = new AgentLoop(loopConfig, promptBuilder, permissionEngine,
                contextManager, autoMemory, hookEngine);
        this.cmdRegistry = cmdRegistry;
        BuiltinCommands.registerAll(cmdRegistry);
        this.dispatcher = new CommandDispatcher(cmdRegistry);
    }

    public void start() {
        try {
            Terminal terminal = TerminalBuilder.builder()
                    .encoding(StandardCharsets.UTF_8)
                    .build();
            lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(new CommandCompleter(dispatcher.getRegistry()))
                    .build();
            printWelcome();
            hookEngine.fire(HookEvent.SESSION_START,
                    HookPayload.forSession(HookEvent.SESSION_START, session.getMessages().toString()));

            while (true) {
                String input;
                try { input = lineReader.readLine("> "); }
                catch (UserInterruptException e) { break; }
                if (input == null || input.isBlank()) continue;

                // 多行检测
                input = input.stripTrailing();
                if (looksIncomplete(input)) {
                    StringBuilder sb = new StringBuilder(input);
                    while (true) {
                        String next = lineReader.readLine("  ");
                        if (next == null) break;
                        next = next.stripTrailing();
                        if (next.isEmpty()) break;
                        sb.append("\n").append(next);
                        if (!looksIncomplete(next)) break;
                    }
                    input = sb.toString().trim();
                }

                // ★ 分流：/ 开头走命令分发
                if (input.startsWith("/")) {
                    String feedText = dispatcher.dispatch(input, this);
                    if (feedText != null) {
                        // PROMPT_FEED：注入消息 + 触发 LLM
                        processMessage(feedText);
                    }
                } else {
                    processMessage(input);
                }
            }
        } catch (Exception e) {
            System.err.println("REPL 启动失败: " + e.getClass().getName() + " - " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void processMessage(String input) {
        session.addMessage(new ChatMessage("user", input));
        try {
            ToolRegistry activeTools = buildActiveToolRegistry();
            List<ChatMessage> history = new ArrayList<>(session.getMessages());
            final BlockingQueue<AgentEvent> eventQueue
                    = agentLoop.run(history, currentProvider, activeTools, lineReader);
            StringBuilder fullReply = new StringBuilder();
            consumeEvents(eventQueue, fullReply);
            syncSessionFromHistory(history);
            System.out.println();
        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
        }
    }

    private void consumeEvents(BlockingQueue<AgentEvent> queue, StringBuilder fullReply) {
        try {
            while (true) {
                AgentEvent event = queue.poll(100, TimeUnit.MILLISECONDS);
                try {
                    if (lineReader.getTerminal().reader() != null && lineReader.getTerminal().reader().ready()) {
                        if (lineReader.getTerminal().reader().read() == 3) {
                            agentLoop.cancel(); display("[已取消]"); break;
                        }
                    }
                } catch (Exception ignored) {}
                if (event == null) continue;
                switch (event.type()) {
                    case TEXT -> { System.out.print(event.text()); System.out.flush(); fullReply.append(event.text()); }
                    case TOOL_CALL -> display("\n[" + event.iteration() + "/" + event.totalIterations()
                            + " 调用工具: " + event.toolCall().name() + "]");
                    case TOOL_RESULT -> {
                        String s = event.toolResult().content();
                        display("[" + (event.toolResult().success() ? "OK" : "FAIL")
                                + " " + (s.length() > 200 ? s.substring(0,200)+"..." : s).replace("\n","\\n") + "]");
                    }
                    case ERROR -> { display("[停止: " + event.text() + "]"); return; }
                    case DONE -> { return; }
                    default -> {}
                }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void syncSessionFromHistory(List<ChatMessage> history) {
        List<ChatMessage> cur = session.getMessages();
        if (history.size() > cur.size()) {
            for (int i = cur.size(); i < history.size(); i++) session.addMessage(history.get(i));
        }
    }

    private ToolRegistry buildActiveToolRegistry() {
        if (planMode) {
            ToolRegistry ro = new ToolRegistry();
            for (String n : READ_ONLY_TOOLS) toolRegistry.get(n).ifPresent(ro::register);
            return ro;
        }
        return toolRegistry;
    }

    private static boolean looksIncomplete(String line) {
        if (line == null || line.isEmpty()) return false;
        String s = line.stripTrailing();
        if (s.endsWith("{") || s.endsWith("(") || s.endsWith(",")) return true;
        if (s.endsWith(";") || s.endsWith("}")) return false;
        if (!s.isEmpty() && Character.isWhitespace(line.charAt(0))) return true;
        return false;
    }

    // ── Console 接口实现 ──

    @Override public void display(String msg) {
        lineReader.getTerminal().writer().println(msg);
        lineReader.getTerminal().writer().flush();
    }

    @Override public void sendUserMessage(String text) { processMessage(text); }

    @Override public void switchMode(String modeName) {
        permissionEngine.setMode(com.rook.permission.PermissionMode.fromString(modeName));
    }

    @Override public void setPlanMode(boolean plan) {
        this.planMode = plan;
        if (plan) agentLoop.setPlanMode(true);
        else agentLoop.triggerDoMode();
    }

    @Override public void compact() { contextManager.compress(currentProvider); }

    @Override public void clearSession() { session.clear(); }

    @Override public void resumeSession() { display("会话恢复暂未实现"); }

    @Override public void exit() { System.exit(0); }

    @Override public String permissionMode() { return permissionEngine.getMode().name(); }

    @Override public String tokenUsage() {
        var u = currentProvider.lastUsage();
        return u.inputTokens() + "/" + u.outputTokens();
    }

    @Override public int toolCount() { return toolRegistry.size(); }

    @Override public int memoryCount() { return 0; } // ch08 记忆暂未接入计数

    @Override public String modelName() { return currentModel; }

    @Override public String workingDir() { return System.getProperty("user.dir"); }

    @Override public String sessionPath() { return "~/.rook/sessions/default.json"; }

    @Override public String memoryFiles() { return "(待实现)"; }

    @Override public LineReader getLineReader() { return lineReader; }

    /** @return 方法返回值 */
    public Session getSession() { return session; }

    // ── Console Worktree ──
    @Override public String worktreeCreate(String s) {
        try { return wtManager.create(s).toString(); } catch (Exception e) { return "创建失败: " + e.getMessage(); }
    }
    @Override public String worktreeList() {
        var slugs = wtManager.list();
        return slugs.isEmpty() ? "无活跃 Worktree"
                : String.join("\n", slugs.stream().map(x -> "  " + x + " → " + wtManager.getPath(x)).toList());
    }
    @Override public String worktreeRemove(String s, boolean d) {
        try { wtManager.remove(s, d); return "已删除: " + s; } catch (Exception e) { return "删除失败: " + e.getMessage(); }
    }

    private void printWelcome() {
        display("Rook v0.1.0 | " + currentProvider.name() + " | " + toolRegistry.size() + " 工具");
        display("输入 /help 查看命令 | /exit 退出");
        display("");
    }
}
