package com.mewcode.cmd;

import java.util.*;

/** 命令注册中心 —— 启动时注册，检测别名冲突 panic。 */
public class CommandRegistry {

    private final Map<String, Command> commands = new LinkedHashMap<>();

    /** 注册一条命令。别名冲突直接抛异常。 */
    public void register(Command cmd) {
        // 检测冲突
        for (String alias : cmd.aliases()) {
            if (commands.containsKey(alias)) {
                throw new IllegalStateException(
                        "命令别名冲突: /" + alias + " (命令 '" + cmd.name()
                        + "' 和已注册的 '" + commands.get(alias).name() + "')");
            }
        }
        if (commands.containsKey(cmd.name())) {
            throw new IllegalStateException(
                    "命令名冲突: /" + cmd.name());
        }

        commands.put(cmd.name(), cmd);
        for (String alias : cmd.aliases()) {
            commands.put(alias, cmd);
        }
    }

    /** 按名称或别名查找命令 */
    public Optional<Command> get(String name) {
        return Optional.ofNullable(commands.get(name.toLowerCase()));
    }

    /** 所有已注册命令（按注册顺序） */
    public List<Command> all() {
        return commands.values().stream()
                .distinct()
                .toList();
    }

    /** 所有非隐藏命令名 */
    public Set<String> names() {
        return commands.keySet();
    }
}
