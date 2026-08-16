package com.rook.team;

import java.util.Set;

/** G15: 两把锁——feature flag + env 变量全开才生效 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class CoordinatorMode {

    // 协调模式下 Lead 保留的工具白名单
    public static final Set<String> COORDINATOR_TOOLS = Set.of(
            "agent", "team_manage", "task_create", "task_get", "task_list", "task_update",
            "send_message", "read_file", "glob", "grep", "exec_command");

    /** 被剥夺的工具（write_file + edit_file）不在白名单中 */

    /** 检查是否启用 Coordinator 模式 */
    public static boolean isEnabled() {
        // G15: 两把锁
        boolean featureFlag = "true".equalsIgnoreCase(
                System.getProperty("rook.coordinator", "false"));
        boolean envTruthy = "true".equalsIgnoreCase(
                System.getenv("ROOK_COORDINATOR_MODE"));
        return featureFlag && envTruthy;
    }

    /** Coordinator 模式下的系统提示词 */
    public static String systemPrompt() {
        return """
                你是 Team Lead，当前处于 Coordinator 模式。
                你只能进行以下操作：
                  1. Research — 用 read_file/glob/grep 探索代码
                  2. Synthesis — 分析信息、制定任务计划
                  3. Implementation — 派队员(agent工具)执行具体修改
                  4. Verification — 审查队员结果、用 git merge 合并
                你自己不能 write_file / edit_file ——代码修改请派队员完成。""";
    }
}
