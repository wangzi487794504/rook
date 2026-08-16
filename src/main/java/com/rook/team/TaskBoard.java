package com.rook.team;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** G6: 共享任务清单，含依赖字段 blocks/blockedBy */
/**
 * @author wangzijie
 * @since 0.1.0
 */
public class TaskBoard {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final ConcurrentHashMap<String, Task> tasks = new ConcurrentHashMap<>();

    /** @return 方法返回值 */
    public TaskBoard(Path teamDir) {
        this.file = teamDir.resolve("tasks.json");
    }

    public void load() throws IOException {
        if (!Files.exists(file)) return;
        Task[] arr = MAPPER.readValue(file.toFile(), Task[].class);
        for (Task t : arr) tasks.put(t.id, t);
    }

    public void save() throws IOException {
        Files.createDirectories(file.getParent());
        MAPPER.writeValue(file.toFile(), new ArrayList<>(tasks.values()));
    }

    /** @return 方法返回值 */
    public Task create(String subject, String description) {
        String id = "t" + System.currentTimeMillis();
        Task t = new Task(id, subject, description, "pending", new ArrayList<>(), new ArrayList<>());
        tasks.put(id, t);
        return t;
    }

    /** @return 方法返回值 */
    public Optional<Task> get(String id) { return Optional.ofNullable(tasks.get(id)); }
    /** @return 方法返回值 */
    public List<Task> list() { return new ArrayList<>(tasks.values()); }
    public void update(Task t) { tasks.put(t.id, t); }

    public static class Task {
        public String id, subject, description, status;
        public List<String> blocks, blockedBy;
        public Task() {} // Jackson
        public Task(String id, String subject, String description, String status,
                     List<String> blocks, List<String> blockedBy) {
            this.id = id; this.subject = subject; this.description = description;
            this.status = status; this.blocks = blocks; this.blockedBy = blockedBy;
        }
    }
}
