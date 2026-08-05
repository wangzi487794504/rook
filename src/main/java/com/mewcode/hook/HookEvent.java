package com.mewcode.hook;

/** Agent 生命周期 11 个事件 */
public enum HookEvent {
    SESSION_START,      // 启动或 /clear 后，首条消息写入前
    SESSION_END,        // 退出或 /clear 关闭旧会话前
    SESSION_RESUME,     // /resume 恢复后
    USER_PROMPT_SUBMIT,  // 用户提交消息，写入历史前 — 可拦截
    STOP,               // Agent.Run 自然停止后
    PRE_USER_MESSAGE,   // 每轮调 provider.stream 前
    PRE_TOOL_USE,       // 单条 tool 执行前 — 可拦截
    POST_TOOL_USE,      // 单条 tool 拿到结果后
    PRE_COMPACT,        // compact 调用前
    POST_COMPACT,       // compact 返回后
    NOTIFICATION        // 权限审批弹出 / Stream 报错时
}
