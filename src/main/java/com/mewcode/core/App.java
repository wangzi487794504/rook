package com.mewcode.core;

import com.mewcode.cli.Repl;
import com.mewcode.config.AppConfig;
import com.mewcode.config.ConfigLoader;
import com.mewcode.context.ContextConfig;
import com.mewcode.context.ContextManager;
import com.mewcode.core.agent.LoopConfig;
import com.mewcode.memory.AutoMemory;
import com.mewcode.memory.MemoryIndex;
import com.mewcode.memory.ProjectInstructions;
import com.mewcode.memory.SessionArchiver;
import com.mewcode.mcp.McpManager;
import com.mewcode.prompt.PromptBuilder;
import com.mewcode.hook.HookEngine;
import com.mewcode.skills.SkillDef;
import com.mewcode.skills.SkillLoader;
import com.mewcode.subagent.AgentDefLoader;
import com.mewcode.subagent.AgentTool;
import com.mewcode.worktree.WorktreeManager;
import com.mewcode.skills.SkillRegistry;
import com.mewcode.skills.SkillTool;
import com.mewcode.prompt.modules.*;
import com.mewcode.llm.ChatMessage;
import com.mewcode.llm.LlmProvider;
import com.mewcode.llm.ProviderFactory;
import com.mewcode.permission.*;
import com.mewcode.session.Session;
import com.mewcode.tools.ToolRegistry;
import com.mewcode.tools.impl.*;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动协调器：按顺序初始化各模块并启动 REPL。
 *
 * @author MewCode
 * @since 0.1.0
 */
public class App {

    private static final Path SESSIONS_DIR = Paths.get(
            System.getProperty("user.home"), ".mewcode", "sessions");
    private static final String SESSION_FILE = "default.json";

    /**
     * 启动 MewCode。
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
        Path builtinSkills = Paths.get("src/main/java/com/mewcode/skills/builtin");
        Path userSkills = userHome.resolve(".mewcode/skills");
        Path projectSkills = projectRoot.resolve(".mewcode/skills");
        SkillRegistry skillRegistry = new SkillRegistry();
        SkillLoader.loadAll(builtinSkills, userSkills, projectSkills)
                .forEach((name, def) -> skillRegistry.register(def));
        // 命令注册中心（提前创建，给 SkillTool 用）
        com.mewcode.cmd.CommandRegistry cmdRegistry = new com.mewcode.cmd.CommandRegistry();
        // 注册 skill_load 系统工具
        toolRegistry.register(new SkillTool(skillRegistry, cmdRegistry));
        // 启动时自动注册所有 Skill 为斜杠命令（冲突时跳过，内置优先）
        System.out.println("[Skill] 已加载 " + skillRegistry.count() + " 个 Skill");


        // 注册 Agent 工具（子Agent委派）
        var agentLoader = new AgentDefLoader();
        Path builtinAgents = Paths.get("src/main/java/com/mewcode/subagent/builtin");
        List<ChatMessage> emptyHistory = List.of();
        toolRegistry.register(new AgentTool(defaultProvider, toolRegistry,
                List.of("agent"), emptyHistory,
                agentLoader, builtinAgents, userSkills, projectSkills, projectRoot));
        // 2d. 发现并注册 MCP 外部工具
        McpManager mcpManager = new McpManager();
        mcpManager.discover(
                Paths.get(System.getProperty("user.home"), ".mewcode", "mcp.yaml"),
                Paths.get("").toAbsolutePath().resolve(".mewcode/mcp.yaml"),
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
                Paths.get(System.getProperty("user.home"), ".mewcode", "rules.yaml"),
                projectRoot.resolve(".mewcode/rules.yaml"),
                projectRoot.resolve(".mewcode/rules.local.yaml"));
        PathSandbox sandbox = new PathSandbox(projectRoot);
        HumanInTheLoop human = new HumanInTheLoop(projectRoot.resolve(".mewcode/rules.local.yaml"));
        PermissionEngine permissionEngine = new PermissionEngine(sandbox, ruleEngine, human);

        // 3d. 加载项目指令
        
        String instructions = ProjectInstructions.load(projectRoot, userHome);
        if (!instructions.isBlank()) {
            System.out.println("[Memory] 已加载项目指令");
        }

        // 3e. 记忆系统
        Path memoryDir = userHome.resolve(".mewcode/memory");
        MemoryIndex memIndex = new MemoryIndex(memoryDir.resolve("MEMORY.md"));
        AutoMemory autoMemory = new AutoMemory(memoryDir, memIndex);
        // 清理过期会话
        SessionArchiver.cleanOld(
                userHome.resolve(".mewcode/sessions"), 30);
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
        Path contextDir = Paths.get(System.getProperty("user.home"), ".mewcode", "context");
        ContextManager contextManager = new ContextManager(ctxConfig, contextDir, "{}");

        // 3g. Hook 引擎
        HookEngine hookEngine = new HookEngine(
                userHome.resolve(".mewcode/hooks.yaml"),
                projectRoot.resolve(".mewcode/hooks.yaml"));

        // 3h. Worktree 管理器
        WorktreeManager wtManager = new WorktreeManager(projectRoot);
        wtManager.loadSession(); // G11: 恢复上次 session

        // 4. 启动 REPL
        String defaultModel = providerModels.getOrDefault(appConfig.defaultProvider(), "unknown");
        LoopConfig loopConfig = new LoopConfig();
        Repl repl = new Repl(providers, providerModels, defaultProvider, defaultModel,
                session, toolRegistry, loopConfig, promptBuilder, permissionEngine, mcpManager,
                contextManager, autoMemory, cmdRegistry, hookEngine, wtManager);

        // ★ Skill 斜杠命令注册（放在 BuiltinCommands 之后，冲突静默跳过）
        for (SkillDef s : skillRegistry.all()) {
            try {
                cmdRegistry.register(new com.mewcode.cmd.Command(s.name(), List.of(),
                        s.description(), "/" + s.name(), com.mewcode.cmd.CommandType.PROMPT_FEED, false,
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
