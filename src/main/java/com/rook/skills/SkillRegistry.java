package com.rook.skills;

import java.util.*;

/** 已加载的 Skill 集合（轻量：仅 name+desc），激活管理 */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class SkillRegistry {

    private final Map<String, SkillDef> allSkills = new LinkedHashMap<>();
    private final Map<String, SkillDef> activeSkills = new LinkedHashMap<>();

    public void register(SkillDef skill) {
        allSkills.put(skill.name(), skill);
    }

    /** @return 方法返回值 */
    public Optional<SkillDef> get(String name) {
        return Optional.ofNullable(allSkills.get(name));
    }

    /** 注入到上下文的第一形态 */
    /** @return 方法返回值 */
    public String toPromptText() {
        if (allSkills.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n## 可用 Skill\n");
        for (SkillDef s : allSkills.values()) {
            sb.append("- /").append(s.name()).append(": ").append(s.description()).append("\n");
        }
        return sb.toString();
    }

    /** 激活一个 Skill */
    public void activate(String name) {
        SkillDef s = allSkills.get(name);
        if (s != null) activeSkills.put(name, s);
    }

    /** 清空所有激活 */
    public void clear() { activeSkills.clear(); }

    /** 当前激活的 Skill */
    /** @return 方法返回值 */
    public Collection<SkillDef> active() { return activeSkills.values(); }

    /** 当前激活 Skill 的合并工具白名单（null 表示不限制） */
    /** @return 方法返回值 */
    public List<String> mergedToolWhitelist() {
        Set<String> whitelist = null;
        for (SkillDef s : activeSkills.values()) {
            if (s.allowedTools().isEmpty()) continue;
            if (whitelist == null) whitelist = new LinkedHashSet<>();
            whitelist.addAll(s.allowedTools());
        }
        return whitelist == null ? null : new ArrayList<>(whitelist);
    }

    /** 所有已注册 Skill */
    /** @return 方法返回值 */
    public Collection<SkillDef> all() { return allSkills.values(); }
    public int count() { return allSkills.size(); }
}
