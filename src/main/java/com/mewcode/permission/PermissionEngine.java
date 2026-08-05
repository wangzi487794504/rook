package com.mewcode.permission;

import com.mewcode.tools.ToolCall;
import org.jline.reader.LineReader;

import java.util.Set;

/**
 * 权限引擎 —— 五层串联调度，逐层短路。
 *
 * @author MewCode
 * @since 0.5.0
 */
public class PermissionEngine {

    private final Blacklist blacklist;
    private final PathSandbox sandbox;
    private final RuleEngine ruleEngine;
    private final HumanInTheLoop human;
    private volatile PermissionMode mode = PermissionMode.DEFAULT;

    public PermissionEngine(PathSandbox sandbox, RuleEngine ruleEngine, HumanInTheLoop human) {
        this.blacklist = new Blacklist();
        this.sandbox = sandbox;
        this.ruleEngine = ruleEngine;
        this.human = human;
    }

    // 系统级工具列表（不受权限约束）
    private static final Set<String> SYSTEM_TOOLS = Set.of("skill_load");

    /** 五层串联检查。系统工具直接放行。 */
    public PermissionResult check(ToolCall call, LineReader reader) {
        if (SYSTEM_TOOLS.contains(call.name())) {
            return PermissionResult.allow();
        }
        // 第1层：黑名单（不可绕过）
        PermissionResult r1 = blacklist.check(call);
        if (!r1.isAllowed()) return r1;

        // 第2层：沙箱
        PermissionResult r2 = sandbox.check(call);
        if (!r2.isAllowed()) return r2;

        // 第3层：规则引擎
        PermissionResult r3 = ruleEngine.check(call);
        if (!r3.isAllowed()) return r3;

        // 第4层：模式兜底
        PermissionVerdict modeVerdict = mode.verdictFor(call.name());
        if (modeVerdict == PermissionVerdict.ALLOW) {
            return PermissionResult.allow();
        }
        if (modeVerdict == PermissionVerdict.DENY) {
            return PermissionResult.deny("当前模式 '" + mode.name() + "' 禁止此操作", "mode");
        }

        // 第5层：人在回路（modeVerdict == ASK）
        if (reader != null) {
            return human.ask(call, "模式为 " + mode.name() + "，需要您确认此操作", reader);
        }
        return PermissionResult.deny("需要用户确认但当前无交互终端", "human");
    }

    public void setMode(PermissionMode mode) { this.mode = mode; }
    public PermissionMode getMode() { return mode; }
}
