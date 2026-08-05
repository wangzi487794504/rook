package com.mewcode.cmd;

import org.jline.reader.LineReader;

/**
 * UI 抽象接口 —— 命令不绑定具体渲染框架，通过此接口访问 UI 能力。
 * Repl 类实现此接口。
 */
public interface Console {

    /** 向用户打印一行消息 */
    void display(String msg);

    /** 向对话注入一条用户消息 + 触发 LLM 回合（PROMPT_FEED 类型命令用） */
    void sendUserMessage(String text);

    /** 切换权限模式 */
    void switchMode(String modeName);

    /** 进入/退出计划模式 */
    void setPlanMode(boolean plan);

    /** 触发上下文压缩 */
    void compact();

    /** 清空当前会话，开新会话 */
    void clearSession();

    /** 打开会话列表 */
    void resumeSession();

    /** 退出进程 */
    void exit();

    // ── 查询方法 ──

    /** 当前权限模式名称 */
    String permissionMode();

    /** 累计 token 用量 */
    String tokenUsage();

    /** 可用工具数量 */
    int toolCount();

    /** 已加载记忆条目数 */
    int memoryCount();

    /** 当前模型名 */
    String modelName();

    /** 当前工作目录 */
    String workingDir();

    /** 当前会话文件路径 */
    String sessionPath();

    /** 记忆文件列表 */
    String memoryFiles();

    /** JLine 的 LineReader（补全菜单用） */
    LineReader getLineReader();

    // ── Worktree ──
    /** 创建 Worktree，返回路径或错误信息 */
    String worktreeCreate(String slug);
    /** 列出所有 Worktree */
    String worktreeList();
    /** 删除 Worktree。discardChanges 强制删除 */
    String worktreeRemove(String slug, boolean discardChanges);
}
