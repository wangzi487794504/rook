package com.rook.core;

import com.rook.cli.Repl;
import com.rook.config.AppConfig;
import com.rook.config.ConfigLoader;
import com.rook.context.ContextConfig;
import com.rook.context.ContextManager;
import com.rook.core.agent.LoopConfig;
import com.rook.memory.AutoMemory;
import com.rook.memory.MemoryIndex;
import com.rook.memory.ProjectInstructions;
import com.rook.memory.SessionArchiver;
import com.rook.mcp.McpManager;
import com.rook.prompt.PromptBuilder;
import com.rook.hook.HookEngine;
import com.rook.skills.SkillDef;
import com.rook.skills.SkillLoader;
import com.rook.subagent.AgentDefLoader;
import com.rook.subagent.AgentTool;
import com.rook.team.TeamManager;
import com.rook.team.TeamTool;
import com.rook.team.SendMessageTool;
import com.rook.worktree.WorktreeManager;
import com.rook.skills.SkillRegistry;
import com.rook.skills.SkillTool;
import com.rook.prompt.modules.*;
import com.rook.llm.ChatMessage;
import com.rook.llm.LlmProvider;
import com.rook.llm.ProviderFactory;
import com.rook.permission.*;
import com.rook.session.Session;
import com.rook.tools.ToolRegistry;
import com.rook.tools.impl.*;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动协调器：按顺序初始化各模块并启动 REPL。
 *
 * @author wangzijie
 * @since 0.1.0
 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class App {

    private static final Path SESSIONS_DIR = Paths.get(
            System.getProperty("user.home"), ".rook", "sessions");
    private static final String SESSION_FILE = "default.json";

    /**
     * 启动 Rook。
     *
     * @param configPath 配置文件路径
     */
    public void run(Path configPath) {
        System.out.println("正在加载配置...");

        // 1. 加载配置
        AppConfig appConfig;
        try {
            appConfig = ConfigLoader.load(configPath);
        } catch (IOException | IllegalStateException e) {
            System.err.println("配置加载失败: " + e.getMessage());
            System.exit(1);
            return;
        }

        // 2. 创建所有 Provider
        Map<String, LlmProvider> providers = ProviderFactory.create(appConfig);
        LlmProvider defaultProvider = providers.get(appConfig.defaultProvider());
        if (defaultProvider == null) {
            System.err.println("默认 Provider '" + appConfig.defaultProvider() + "' 不存在");
            System.exit(1);
            return;
        }

        // 2a. 构建 model 映射
        Map<String, String> providerModels = new HashMap<>();
        for (var pc : appConfig.providers()) {
            providerModels.put(pc.name(), pc.model());
        }

        // 2b. 常用路径
        Path userHome = Paths.get(System.getProperty("user.home"));
        Path projectRoot = Paths.get(System.getProperty("user.dir"));

        // 2c. 创建工具注册中心
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new ReadFileTool());
        toolRegistry.register(new WriteFileTool());
        toolRegistry.register(new EditFileTool());
        toolRegistry.register(new ExecCommandTool());
        toolRegistry.register(new GlobTool());
        toolRegistry.register(new GrepTool());
        toolRegistry.register(new WebSearchTool());

        // 2c. 加载 Skill 系统
        Path builtinSkills = Paths.get("src/main/java/com/rook/skills/builtin");
        Path userSkills = userHome.resolve(".rook/skills");
        Path projectSkills = projectRoot.resolve(".rook/skills");
        SkillRegistry skillRegistry = new SkillRegistry();
        SkillLoader.loadAll(builtinSkills, userSkills, projectSkills)
                .forEach((name, def) -> skillRegistry.register(def));
        // 命令注册中心（提前创建，给 SkillTool 用）
        com.rook.cmd.CommandRegistry cmdRegistry = new com.rook.cmd.CommandRegistry();
        // 注册 skill_load 系统工具
        toolRegistry.register(new SkillTool(skillRegistry, cmdRegistry));
        // 启动时自动注册所有 Skill 为斜杠命令（冲突时跳过，内置优先）
        System.out.println("[Skill] 已加载 " + skillRegistry.count() + " 个 Skill");


        // 注册 Agent 工具（子Agent委派）
        var agentLoader = new AgentDefLoader();
        Path builtinAgents = Paths.get("src/main/java/com/rook/subagent/builtin");
        List<ChatMessage> emptyHistory = List.of();
        toolRegistry.register(new AgentTool(defaultProvider, toolRegistry,
                List.of("agent"), emptyHistory,
                agentLoader, builtinAgents, userSkills, projectSkills, projectRoot));
        // 2d. 发现并注册 MCP 外部工具
        McpManager mcpManager = new McpManager();
        mcpManager.discover(
                Paths.get(System.getProperty("user.home"), ".rook", "mcp.yaml"),
                Paths.get("").toAbsolutePath().resolve(".rook/mcp.yaml"),
                toolRegistry);

        // 3. 加载或创建会话
        Path sessionPath = SESSIONS_DIR.resolve(SESSION_FILE);
        Session session;
        try {
            session = Session.load(sessionPath);
        } catch (IOException e) {
            System.err.println("会话加载失败: " + e.getMessage() + "，将创建新会话");
            session = new Session();
        }

        // 3b. 创建权限引擎
        RuleEngine ruleEngine = new RuleEngine(
                Paths.get(System.getProperty("user.home"), ".rook", "rules.yaml"),
                projectRoot.resolve(".rook/rules.yaml"),
                projectRoot.resolve(".rook/rules.local.yaml"));
        PathSandbox sandbox = new PathSandbox(projectRoot);
        HumanInTheLoop human = new HumanInTheLoop(projectRoot.resolve(".rook/rules.local.yaml"));
        PermissionEngine permissionEngine = new PermissionEngine(sandbox, ruleEngine, human);

        // 3d. 加载项目指令
        
        String instructions = ProjectInstructions.load(projectRoot, userHome);
        if (!instructions.isBlank()) {
            System.out.println("[Memory] 已加载项目指令");
        }

        // 3e. 记忆系统
        Path memoryDir = userHome.resolve(".rook/memory");
        MemoryIndex memIndex = new MemoryIndex(memoryDir.resolve("MEMORY.md"));
        AutoMemory autoMemory = new AutoMemory(memoryDir, memIndex);
        // 清理过期会话
        SessionArchiver.cleanOld(
                userHome.resolve(".rook/sessions"), 30);
        // 3c. 创建 PromptBuilder
        PromptBuilder promptBuilder = new PromptBuilder();
        promptBuilder.register(new IdentityModule());
        promptBuilder.register(new SystemConstraintsModule());
        promptBuilder.register(new TaskModeModule());
        promptBuilder.register(new ActionExecutionModule());
        promptBuilder.register(new ToolUsageModule());
        promptBuilder.register(new ToneStyleModule());
        promptBuilder.register(new TextOutputModule());
        CustomInstructionsModule customInst = new CustomInstructionsModule();
        String ci = instructions.isBlank() ? "" : instructions;
        if (!ci.isBlank()) ci += "\n";
        ci += memIndex.loadForPrompt();
        ci += skillRegistry.toPromptText();
        if (!ci.isBlank()) customInst.setContent(ci);
        promptBuilder.register(customInst);
        promptBuilder.register(new ActiveSkillsModule());
        promptBuilder.register(new LongTermMemoryModule());


        // 3f. 创建上下文管理器
        ContextConfig ctxConfig = new ContextConfig();
        Path contextDir = Paths.get(System.getProperty("user.home"), ".rook", "context");
        ContextManager contextManager = new ContextManager(ctxConfig, contextDir, "{}");

        // 3g. Hook 引擎
        HookEngine hookEngine = new HookEngine(
                userHome.resolve(".rook/hooks.yaml"),
                projectRoot.resolve(".rook/hooks.yaml"));

        // 3h. Worktree + Team 管理器
        WorktreeManager wtManager = new WorktreeManager(projectRoot);
        wtManager.loadSession();
        TeamManager teamManager = new TeamManager(wtManager); // G11: 恢复上次 session

        // 注册 Team 协作工具(需 teamManager 已创建)
        toolRegistry.register(new TeamTool(teamManager));
        toolRegistry.register(new SendMessageTool(teamManager));

        // 4. 启动 REPL
        String defaultModel = providerModels.getOrDefault(appConfig.defaultProvider(), "unknown");
        LoopConfig loopConfig = new LoopConfig();
        Repl repl = new Repl(providers, providerModels, defaultProvider, defaultModel,
                session, toolRegistry, loopConfig, promptBuilder, permissionEngine, mcpManager,
                contextManager, autoMemory, cmdRegistry, hookEngine, wtManager, teamManager);

        // ★ Skill 斜杠命令注册（放在 BuiltinCommands 之后，冲突静默跳过）
        for (SkillDef s : skillRegistry.all()) {
            try {
                cmdRegistry.register(new com.rook.cmd.Command(s.name(), List.of(),
                        s.description(), "/" + s.name(), com.rook.cmd.CommandType.PROMPT_FEED, false,
                        (console, args) -> {
                            skillRegistry.activate(s.name());
                            String body = s.body().replace("$ARGUMENTS", args != null ? args : "");
                            return body;
                        }));
            } catch (IllegalStateException ignored) {
                // 内置命令優先，Skill 同名跳过
            }
        }

        // 注册关闭钩子，确保退出时保存会话
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                repl.getSession().save(sessionPath);
            } catch (IOException e) {
                System.err.println("会话保存失败: " + e.getMessage());
            }
        }));

        repl.start();

        // REPL 退出后保存
        try {
            repl.getSession().save(sessionPath);
            System.out.println("会话已保存到 " + sessionPath);
        } catch (IOException e) {
            System.err.println("会话保存失败: " + e.getMessage());
        }
    }
}
