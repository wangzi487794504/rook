package com.rook.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rook.worktree.WorktreeManager;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** G1: 单进程内管理多个 Team */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class TeamManager {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path TEAMS_ROOT = Paths.get(System.getProperty("user.home"), ".rook", "teams");

    private final Map<String, Team> teams = new ConcurrentHashMap<>();
    private final Map<String, String> agentNameRegistry = new ConcurrentHashMap<>(); // name → agentId
    private final WorktreeManager wtManager;

    /** @return 方法返回值 */
    public TeamManager(WorktreeManager wtManager) { this.wtManager = wtManager; }

    /** G2: TeamCreate */
    /** @return 方法返回值 */
    public Team create(String name, String leadId) throws IOException {
        String safe = sanitize(name);
        int suffix = 1;
        String base = safe;
        while (teams.containsKey(safe) || Files.exists(TEAMS_ROOT.resolve(safe))) {
            safe = base + "-" + (++suffix);
        }
        Path dir = TEAMS_ROOT.resolve(safe);
        Files.createDirectories(dir);

        Team team = new Team(safe, leadId);
        team.setConfigPath(dir.resolve("config.json"));
        // Lead 是第一成员
        Member lead = new Member("Lead", leadId, "lead", null, "in-process", false);
        team.addMember(lead);
        agentNameRegistry.put("Lead", leadId);
        agentNameRegistry.put(leadId, leadId);

        team.save();
        teams.put(safe, team);
        return team;
    }

    /** G3: 扩展 Agent 工具 spawn */
    /** @return 方法返回值 */
    public Member spawnMember(String teamName, String agentType, String memberName) throws IOException {
        Team team = teams.get(teamName);
        if (team == null) throw new IllegalArgumentException("Team 不存在: " + teamName);

        String agentId = "agent-" + UUID.randomUUID().toString().substring(0, 7);
        String wtSlug = "team-" + teamName + "+" + memberName;
        Path worktree = wtManager.create(wtSlug);

        Member member = new Member(memberName, agentId, agentType,
                worktree.toString(), "in-process", false);
        team.addMember(member);
        agentNameRegistry.put(memberName, agentId);
        agentNameRegistry.put(agentId, agentId);
        team.save();
        return member;
    }

    /** G4: TeamDelete */
    public void delete(String name) throws IOException {
        Team team = teams.get(name);
        if (team == null) throw new IllegalArgumentException("Team 不存在: " + name);
        boolean anyActive = team.members().stream().anyMatch(Member::isActive);
        if (anyActive) throw new IllegalStateException("有活跃成员，拒绝删除");
        for (Member m : team.members()) {
            if (m.worktree() != null) {
                try { wtManager.remove(WorktreeManager.toFlatSlug(
                        "team-" + name + "+" + m.name()), true); } catch (Exception ignored) {}
            }
            agentNameRegistry.remove(m.name());
            agentNameRegistry.remove(m.agentId());
        }
        teams.remove(name);
        Files.deleteIfExists(team.configPath());
    }

    /** @return 方法返回值 */
    public Optional<Team> get(String name) { return Optional.ofNullable(teams.get(name)); }
    /** @return 方法返回值 */
    public List<Team> list() { return new ArrayList<>(teams.values()); }
    /** @return 方法返回值 */
    public String resolveAgentId(String nameOrId) {
        return agentNameRegistry.getOrDefault(nameOrId, nameOrId);
    }

    /** 检测执行后端 G5 */
    public static String detectBackend() {
        if (System.getenv("TMUX") != null) return "tmux";
        if ("iTerm.app".equals(System.getenv("TERM_PROGRAM"))) return "iterm2";
        return "in-process";
    }

    public static String sanitize(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-|-$", "");
    }
}
