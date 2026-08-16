package com.rook.team;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** 小组对象：名称/Lead/成员/持久化 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class Team {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String name;
    private String leadAgentId;
    private final List<Member> members = new CopyOnWriteArrayList<>();
    private Path configPath;

    /** @return 方法返回值 */
    public Team() {} // Jackson
    /** @return 方法返回值 */
    public Team(String name, String leadId) {
        this.name = name; this.leadAgentId = leadId;
    }

    /** @return 方法返回值 */
    public String name() { return name; }
    /** @return 方法返回值 */
    public String leadAgentId() { return leadAgentId; }
    /** @return 方法返回值 */
    public List<Member> members() { return members; }
    /** @return 方法返回值 */
    public Path configPath() { return configPath; }

    public void setName(String n) { name = n; }
    public void setLeadAgentId(String id) { leadAgentId = id; }
    public void setConfigPath(Path p) { configPath = p; }

    public void addMember(Member m) { members.add(m); }
    /** @return 方法返回值 */
    public Optional<Member> findMember(String nameOrId) {
        return members.stream()
                .filter(m -> m.name().equals(nameOrId) || m.agentId().equals(nameOrId))
                .findFirst();
    }

    /** 持久化到 config.json */
    public void save() throws IOException {
        if (configPath == null) return;
        Files.createDirectories(configPath.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), this);
    }

    /** 从 config.json 恢复 */
    public static Team load(Path path) throws IOException {
        Team t = MAPPER.readValue(path.toFile(), Team.class);
        t.configPath = path;
        return t;
    }
}
