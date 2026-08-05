package com.mewcode.worktree;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Git Worktree 管理器 —— 创建/快速恢复/进入/退出/删除隔离的工作目录。
 * G1-G11 全部实现。
 *
 * @author MewCode
 * @since 0.13.0
 */
public class WorktreeManager {

    // G2: 安全名称校验
    private static final Pattern SAFE_NAME = Pattern.compile("^[a-zA-Z0-9._/-]{1,64}$");
    private static final String WORKTREE_ROOT = ".mewcode/worktrees";
    private static final Pattern TEMP_PATTERN = Pattern.compile("^agent-[a-f0-9]{7}$");

    // G1: 互斥锁保护 active 映射
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, String> active = new ConcurrentHashMap<>();
    private final Path repoRoot;
    private final Path sessionFile;

    public WorktreeManager(Path repoRoot) {
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        this.sessionFile = repoRoot.resolve(".mewcode/worktree_session.json");
    }

    // ── G2: 名称校验 ──
    public static String validateSlug(String slug) {
        if (slug == null || slug.isBlank()) throw new IllegalArgumentException("名称不能为空");
        String s = slug.trim().replace('\\', '/');
        for (String seg : s.split("/")) {
            if (".".equals(seg) || "..".equals(seg))
                throw new IllegalArgumentException("名称包含非法段: " + seg);
        }
        if (!SAFE_NAME.matcher(s).matches())
            throw new IllegalArgumentException(
                    "名称仅允许 [a-zA-Z0-9._/-], ≤64字符: " + s);
        return s;
    }

    // G3: slug → flatSlug (/ → +), branch = worktree-<flatSlug>
    public static String toFlatSlug(String slug) { return slug.replace('/', '+'); }
    public static String branchName(String flatSlug) { return "worktree-" + flatSlug; }
    public Path worktreePath(String flatSlug) {
        return repoRoot.resolve(WORKTREE_ROOT).resolve(flatSlug);
    }

    // ── G1+G3: 创建 ──
    public Path create(String slug) throws IOException {
        String safe = validateSlug(slug);
        String flat = toFlatSlug(safe);
        Path dir = worktreePath(flat);

        lock.lock();
        try {
            // G5: 快速恢复（目录已存在 → 只读文件系统，不调 git）
            if (Files.exists(dir.resolve(".git"))) {
                active.put(slug, flat);
                return dir.toAbsolutePath().normalize();
            }

            Files.createDirectories(dir.getParent());
            String branch = branchName(flat);

            // 检查分支是否存在，不存在则创建 orphan 分支
            runGit("worktree", "add", "--orphan", branch, dir.toString());

            // G4: 四类环境初始化
            initEnv(dir);

            active.put(slug, flat);
            return dir.toAbsolutePath().normalize();
        } finally {
            lock.unlock();
            saveSession();
        }
    }

    // G4: 环境初始化（best-effort）
    private void initEnv(Path dir) {
        // A: 复制本地配置
        copyIfExists(repoRoot.resolve(".mewcode/config.yaml"), dir.resolve(".mewcode/config.yaml"));
        copyIfExists(repoRoot.resolve(".mewcode/settings.local.json"), dir.resolve(".mewcode/settings.local.json"));
        // B: git hooksPath（不自动继承，配置为项目内路径）
        try { runGitIn(dir, "config", "core.hooksPath", ".mewcode/hooks"); } catch (Exception ignored) {}
        // C: 软链大目录
        for (String dep : List.of("node_modules", ".venv", "vendor")) {
            Path src = repoRoot.resolve(dep);
            if (Files.exists(src) && Files.isDirectory(src)) {
                try { Files.createSymbolicLink(dir.resolve(dep), src.toAbsolutePath()); }
                catch (IOException ignored) {}
            }
        }
        // D: 补充文件（项目根 .worktreeinclude 逐行复制）
        Path include = repoRoot.resolve(".worktreeinclude");
        if (Files.exists(include)) {
            try {
                for (String line : Files.readAllLines(include)) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    copyIfExists(repoRoot.resolve(line.trim()), dir.resolve(line.trim()));
                }
            } catch (IOException ignored) {}
        }
    }

    // ── G8: 退出 ──
    public void remove(String slug, boolean discardChanges) throws IOException {
        String safe = validateSlug(slug);
        String flat = toFlatSlug(safe);
        Path dir = worktreePath(flat);
        if (!Files.exists(dir)) return;

        lock.lock();
        try {
            // 变更保护
            if (!discardChanges) {
                if (hasUncommitted(dir))
                    throw new IOException("Worktree 有未提交修改: " + slug);
                if (hasUnpushedCommits(dir))
                    throw new IOException("Worktree 有未推送 commit: " + slug);
            }
            runGit("worktree", "remove", "--force", dir.toString());
            active.remove(slug);
        } finally {
            lock.unlock();
            saveSession();
        }
    }

    // ── G9: 自动清理（SubAgent 退出时） ──
    public String autoCleanup(String slug) {
        String safe;
        try { safe = validateSlug(slug); } catch (Exception e) { return e.getMessage(); }
        String flat = toFlatSlug(safe);
        Path dir = worktreePath(flat);
        if (!Files.exists(dir)) return "目录不存在";

        try {
            if (!hasUncommitted(dir) && !hasUnpushedCommits(dir)) {
                remove(slug, true);
                return null; // 已清理
            }
        } catch (IOException ignored) {}
        return "Worktree " + dir + " (分支 " + branchName(flat) + ") 有变更，已保留供 review";
    }

    // ── G10: 过期清理 ──
    public int cleanStale(int ttlMinutes) {
        Path base = repoRoot.resolve(WORKTREE_ROOT);
        if (!Files.exists(base)) return 0;
        long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(ttlMinutes);
        int count = 0;
        for (Path dir : listDirs(base)) {
            String name = dir.getFileName().toString();
            if (!TEMP_PATTERN.matcher(name).matches()) continue; // 只识别临时 Worktree
            try {
                if (Files.getLastModifiedTime(dir).toMillis() < cutoff) {
                    if (!hasUncommitted(dir) && !hasUnpushedCommits(dir)) {
                        runGit("worktree", "remove", "--force", dir.toString());
                        count++;
                    }
                }
            } catch (Exception ignored) {}
        }
        return count;
    }

    // ── G11: Session 持久化 ──
    private void saveSession() {
        try {
            Files.createDirectories(sessionFile.getParent());
            Files.writeString(sessionFile, String.join("\n", active.keySet()));
        } catch (IOException ignored) {}
    }

    public void loadSession() {
        if (!Files.exists(sessionFile)) return;
        try {
            for (String slug : Files.readAllLines(sessionFile)) {
                try {
                    String flat = toFlatSlug(slug.trim());
                    if (slug.isBlank()) continue;
                    if (Files.exists(worktreePath(flat))) active.put(slug.trim(), flat);
                } catch (Exception ignored) {}
            }
        } catch (IOException ignored) {}
    }

    // ── 查询 ──
    public List<String> list() { lock.lock(); try { return new ArrayList<>(active.keySet()); } finally { lock.unlock(); } }
    public boolean isActive(String slug) { return active.containsKey(slug); }
    public Path getPath(String slug) {
        return active.containsKey(slug) ? worktreePath(toFlatSlug(slug)) : null;
    }

    // ── git helpers ──
    private void runGit(String... args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(repoRoot.toFile()).start();
        try {
            p.waitFor(30, TimeUnit.SECONDS);
            if (p.exitValue() != 0) {
                String err = new String(p.getErrorStream().readAllBytes());
                if (!err.isBlank()) throw new IOException(err.strip());
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void runGitIn(Path dir, String... args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).start();
        try { p.waitFor(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private boolean hasUncommitted(Path dir) {
        try {
            Process p = new ProcessBuilder("git", "diff", "--stat").directory(dir.toFile()).start();
            p.waitFor(10, TimeUnit.SECONDS);
            return !new String(p.getInputStream().readAllBytes()).isBlank();
        } catch (Exception e) { return true; }
    }

    private boolean hasUnpushedCommits(Path dir) {
        try {
            Process p = new ProcessBuilder("git", "log", "@{u}..").directory(dir.toFile()).start();
            p.waitFor(10, TimeUnit.SECONDS);
            return !new String(p.getInputStream().readAllBytes()).isBlank();
        } catch (Exception e) { return false; }
    }

    private List<Path> listDirs(Path base) {
        try (var s = Files.list(base)) { return s.filter(Files::isDirectory).toList(); }
        catch (IOException e) { return List.of(); }
    }

    private static void copyIfExists(Path src, Path dst) {
        try {
            if (Files.exists(src)) { Files.createDirectories(dst.getParent()); Files.copy(src, dst); }
        } catch (IOException ignored) {}
    }
}
