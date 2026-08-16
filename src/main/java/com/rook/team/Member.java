package com.rook.team;

/** 团队成员 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class Member {
    private String name;          // 显示名
    private String agentId;       // 全局唯一 ID
    private String role;          // 角色/agentType
    private String worktree;      // Worktree 路径
    private String backend;       // in-process | tmux | iterm2
    private boolean needsApproval; // planModeRequired
    private boolean isActive;     // 是否活跃

    /** @return 方法返回值 */
    public Member() {} // Jackson
    /** @return 方法返回值 */
    public Member(String name, String agentId, String role, String worktree, String backend, boolean needsApproval) {
        this.name = name; this.agentId = agentId; this.role = role;
        this.worktree = worktree; this.backend = backend; this.needsApproval = needsApproval;
        this.isActive = true;
    }
    /** @return 方法返回值 */
    public String name() { return name; }
    /** @return 方法返回值 */
    public String agentId() { return agentId; }
    /** @return 方法返回值 */
    public String role() { return role; }
    /** @return 方法返回值 */
    public String worktree() { return worktree; }
    /** @return 方法返回值 */
    public String backend() { return backend; }
    public boolean needsApproval() { return needsApproval; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean a) { isActive = a; }
    public void setName(String n) { name = n; }
    public void setAgentId(String id) { agentId = id; }
    public void setRole(String r) { role = r; }
    public void setWorktree(String w) { worktree = w; }
    public void setBackend(String b) { backend = b; }
    public void setNeedsApproval(boolean a) { needsApproval = a; }
}
