package com.mewcode.cmd;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;

/**
 * 斜杠命令 Tab 补全器 —— 输入 / 后按 Tab 弹出候选菜单，
 * 继续输入按前缀过滤。单匹配直接补全，多匹配弹菜单。
 *
 * @author MewCode
 * @since 0.9.0
 */
public class CommandCompleter implements Completer {

    private final CommandRegistry registry;

    public CommandCompleter(CommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String word = line.word().trim();

        // 只在以 / 开头时激活
        if (!word.startsWith("/")) return;

        // 去掉 / 前缀，获取用户输入的部分命令名
        String prefix = word.substring(1).toLowerCase();

        for (Command cmd : registry.all()) {
            if (cmd.hidden()) continue;

            // 前缀匹配命令名
            if (cmd.name().toLowerCase().startsWith(prefix)) {
                String display = "/" + cmd.name() + "  — " + cmd.description();
                // complete 值不包含描述，只用于补全
                candidates.add(new Candidate(
                        "/" + cmd.name(),
                        display,
                        null, null, null, null, false));
            }
            // 也匹配别名
            for (String alias : cmd.aliases()) {
                if (alias.toLowerCase().startsWith(prefix)) {
                    String display = "/" + alias + "  — " + cmd.description();
                    candidates.add(new Candidate(
                            "/" + alias,
                            display,
                            null, null, null, null, false));
                }
            }
        }

        // 零匹配时显示提示
        if (candidates.isEmpty() && !prefix.isEmpty()) {
            candidates.add(new Candidate(
                    word,
                    "（无匹配命令，输入 /help 查看）",
                    null, null, null, null, false));
        }
    }
}
