# 24 — Worktree 隔离

## 这一章做什么

用 **Git Worktree** 给子 Agent 开独立的文件工作目录。创建 / 恢复 / 退出 / 删除全生命周期管理，退出时变更保护 —— 有改动就保留，没改动就自动清理。

**完成后你能做到：**

- 子 Agent 改代码不影响主工作区
- 多个子 Agent 同时干活，互不干扰
- 子 Agent 干完活，没改动自动清理，有改动保留供 review
- 主 Agent 和子 Agent 共享同一个 Git 仓库（版本库只有一份）

## 为什么需要

### 共享目录的问题

```
主 Agent 在写文件 A
同时子 Agent 也在改文件 A
    │
    ▼
冲突！互相覆盖，谁的改动都没了
```

**具体问题：**

|   问题   |                       说明                       |
| :------: | :----------------------------------------------: |
| 文件冲突 |       主 Agent 和子 Agent 同时改同一个文件       |
| 状态污染 | 子 Agent 的临时改动混在主工作区，git status 很乱 |
| 无法并行 |      只能一个一个来，不能同时派多个子 Agent      |
| 清理麻烦 |     子 Agent 干完活，要手动 revert 它的改动      |
|  风险大  |      子 Agent 误操作可能删掉主 Agent 的工作      |

### Worktree 的解决方案

```
主仓库（主工作区）
├── .git/                    ← 共享的版本库（只有一份）
├── src/                     ← 主 Agent 在这里干活
└── .rook/worktrees/
    ├── agent-explore/       ← 子 Agent 1 的独立目录
    │   ├── .git（文件，指向主仓库）
    │   └── src/
    └── agent-refactor/      ← 子 Agent 2 的独立目录
        ├── .git（文件，指向主仓库）
        └── src/
```

**Git Worktree 是什么？**

- Git 自带的机制
- 同一个仓库可以有多个工作目录
- 共享 `.git` 版本库（对象、引用都在主仓库）
- 每个 worktree 在不同的分支上
- 各自的文件改动互不影响

**好处：**

- 隔离：子 Agent 在自己的目录里随便改
- 共享：版本库只有一份，省空间，commit 互相可见
- 轻量：不需要 clone 整个仓库
- 可并行：多个子 Agent 同时在不同 worktree 干活

## 核心概念

### Git Worktree

- `git worktree add <path> <branch>` 创建独立工作目录
- 共享 `.git` 版本库，各自有独立的工作区和暂存区
- 每个 worktree 必须在不同的分支上（同一个分支不能同时被两个 worktree 检出）

### 路径安全

- 名称校验：`[a-zA-Z0-9._/-]`，长度 ≤ 64
- 拒绝 `.` 和 `..` 段（防路径穿越）
- flatSlug：把 `/` 替换成 `+`，变成扁平目录名

### 环境初始化

- 复制本地配置（`.rook/config.yaml` 等）
- 配置 git hooks（可选）
- 软链大目录（node_modules、target 等，省空间）
- 补充被 `.gitignore` 忽略但运行需要的文件

### 变更保护

- 退出时检测：未提交修改 + 未推送 commit
- 有变更 → 拒绝删除，保留供 review
- 无变更 → 自动删除，干净清理

### 过期清理

- 超过 30 分钟的临时 worktree 自动清理
- 防止子 Agent 崩了留下垃圾

------

## 第一步：Git Worktree 基础

### 常用命令

```
# 创建 worktree（在新分支上）
git worktree add .rook/worktrees/agent-explore -b agent/explore

# 创建 orphan worktree（没有父提交，全新空分支）
git worktree add --orphan .rook/worktrees/agent-explore agent/explore

# 列出所有 worktree
git worktree list

# 删除 worktree
git worktree remove .rook/worktrees/agent-explore

# 强制删除（有未提交改动时）
git worktree remove --force .rook/worktrees/agent-explore

# 清理失效的 worktree（目录被手动删了）
git worktree prune
```

### --orphan 的作用

```
git worktree add --orphan <path> <branch>
```

**普通 worktree：** 从当前分支分叉，有历史

**orphan worktree：** 全新的空分支，没有父提交，工作区是空的

**子 Agent 用 orphan：**

- 干净的起点，不受主分支当前状态影响
- 子 Agent 可以从头开始（比如探索、实验）
- 不需要继承主分支的未提交改动

------

## 第二步：WorktreeManager — 创建与恢复

```
// worktree/WorktreeManager.java
package com.rook.worktree;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class WorktreeManager {

    private final Path repoRoot;
    private final Path worktreeBase;

    // 合法 slug：字母、数字、点、下划线、连字符、斜杠
    private static final Pattern SLUG_PATTERN = 
        Pattern.compile("[a-zA-Z0-9._/-]+");
    private static final int MAX_SLUG_LENGTH = 64;

    public WorktreeManager(Path repoRoot) {
        this.repoRoot = repoRoot;
        this.worktreeBase = repoRoot.resolve(".rook/worktrees");
    }

    /**
     * 创建或恢复 worktree
     * @param slug 标识名（如 "agent-explore"）
     * @return worktree 目录路径
     */
    public Path create(String slug) throws IOException {
        // 1. 校验 slug
        String safe = validateSlug(slug);

        // 2. 计算目录路径（flat slug）
        Path dir = worktreeBase.resolve(toFlatSlug(safe));

        // 3. 已存在且有效 → 快速恢复
        if (Files.exists(dir.resolve(".git"))) {
            System.out.println("[Worktree] 恢复已存在的: " + dir);
            return dir;
        }

        // 4. 确保父目录存在
        Files.createDirectories(worktreeBase);

        // 5. 创建 orphan worktree
        String branchName = branchName(safe);
        runGit("worktree", "add", "--orphan", branchName, dir.toString());

        // 6. 初始化环境
        initEnv(dir);

        System.out.println("[Worktree] 已创建: " + dir + " (分支: " + branchName + ")");
        return dir;
    }

    /**
     * 校验 slug 安全性
     */
    private String validateSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("slug 不能为空");
        }
        if (slug.length() > MAX_SLUG_LENGTH) {
            throw new IllegalArgumentException("slug 太长（最多 " + MAX_SLUG_LENGTH + " 字符）");
        }
        if (!SLUG_PATTERN.matcher(slug).matches()) {
            throw new IllegalArgumentException("slug 包含非法字符: " + slug);
        }
        // 拒绝 . 和 .. 段
        for (String part : slug.split("/")) {
            if (".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("slug 不能包含 . 或 .. 段");
            }
        }
        return slug;
    }

    /**
     * 把 slug 转成扁平目录名
     * agent/explore → agent+explore
     */
    private String toFlatSlug(String slug) {
        return slug.replace("/", "+");
    }

    /**
     * 生成分支名
     * agent-explore → rook/agent-explore
     */
    private String branchName(String slug) {
        return "rook/" + toFlatSlug(slug);
    }

    /**
     * 执行 git 命令
     */
    private void runGit(String... args) throws IOException {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(repoRoot.toString());
        cmd.addAll(List.of(args));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        try {
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            int exit = p.waitFor(30, TimeUnit.SECONDS);
            if (exit != 0) {
                throw new IOException("git 命令失败: " + String.join(" ", args) 
                    + "\n" + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git 命令被中断", e);
        }
    }
}
```

### 逐段详解

#### 1. slug 校验

```
private String validateSlug(String slug) {
    if (slug == null || slug.isBlank()) throw ...;
    if (slug.length() > MAX_SLUG_LENGTH) throw ...;
    if (!SLUG_PATTERN.matcher(slug).matches()) throw ...;
    for (String part : slug.split("/")) {
        if (".".equals(part) || "..".equals(part)) throw ...;
    }
    return slug;
}
```

**★ 路径安全是重中之重。**

**为什么要校验？**

- slug 会被拼到文件路径里
- 不校验的话，`../../etc` 可能穿越到系统目录
- 特殊字符可能导致命令注入

**校验规则：**

1. 非空
2. 长度 ≤ 64
3. 只允许 `[a-zA-Z0-9._/-]`
4. 不能有 `.` 或 `..` 段

#### 2. flatSlug

```
private String toFlatSlug(String slug) {
    return slug.replace("/", "+");
}
```

**为什么把 `/` 换成 `+`？**

- slug 可能有层级，比如 `agent/explore`
- 但 worktree 目录我们想放在同一层（`.rook/worktrees/` 下）
- 换成 `+` 变成扁平目录名：`agent+explore`
- Windows 下 `/` 也是路径分隔符，必须替换

#### 3. 快速恢复

```
if (Files.exists(dir.resolve(".git"))) {
    System.out.println("[Worktree] 恢复已存在的: " + dir);
    return dir;
}
```

**幂等设计：**

- 同一个 slug 多次调用 create，不会重复创建
- 已存在就直接返回
- 子 Agent 重启后能恢复之前的 worktree

#### 4. --orphan 创建

```
runGit("worktree", "add", "--orphan", branchName, dir.toString());
```

**用 --orphan 创建全新空分支：**

- 没有父提交
- 工作区是空的
- 子 Agent 从零开始

**分支名：** `rook/agent-explore`

- 加 `rook/` 前缀，和用户自己的分支区分
- 方便批量管理和清理

#### 5. git -C 指定仓库

```
cmd.add("git");
cmd.add("-C");
cmd.add(repoRoot.toString());
```

**`git -C <path>`：** 在指定目录执行 git 命令。

- 不用 cd
- 确保在主仓库执行 worktree 命令

------

## 第三步：环境初始化

```
// 在 WorktreeManager 中继续

/**
 * 初始化 worktree 环境
 */
private void initEnv(Path dir) throws IOException {
    // 1. 复制本地配置
    copyConfig(dir);

    // 2. 软链大目录（省空间）
    symlinkLargeDirs(dir);

    // 3. 补充被忽略但需要的文件
    supplementIgnoredFiles(dir);

    // 4. 配置 git user（可选，继承主仓库）
    // git config 已经继承了，不需要额外配置
}

/**
 * 复制 .rook 配置到 worktree
 */
private void copyConfig(Path dir) throws IOException {
    Path sourceConfig = repoRoot.resolve(".rook/config.yaml");
    if (Files.exists(sourceConfig)) {
        Path targetConfig = dir.resolve(".rook/config.yaml");
        Files.createDirectories(targetConfig.getParent());
        Files.copy(sourceConfig, targetConfig, 
            StandardCopyOption.REPLACE_EXISTING);
    }
}

/**
 * 软链大目录，避免重复占用空间
 * 比如 node_modules、target、build、.venv 等
 */
private void symlinkLargeDirs(Path dir) throws IOException {
    List<String> largeDirs = List.of(
        "node_modules",
        "target",
        "build",
        ".venv",
        "venv",
        ".gradle"
    );

    for (String name : largeDirs) {
        Path source = repoRoot.resolve(name);
        Path target = dir.resolve(name);

        if (Files.exists(source) && Files.isDirectory(source)) {
            try {
                Files.createSymbolicLink(target, source);
            } catch (IOException e) {
                // Windows 可能需要管理员权限，软链失败就跳过
                System.err.println("[Worktree] 软链失败: " + name + " - " + e.getMessage());
            }
        }
    }
}

/**
 * 补充被 .gitignore 忽略但运行需要的文件
 * 比如 .env、local.properties 等
 */
private void supplementIgnoredFiles(Path dir) throws IOException {
    List<String> ignoredFiles = List.of(
        ".env",
        "local.properties",
        "application-local.yml"
    );

    for (String name : ignoredFiles) {
        Path source = repoRoot.resolve(name);
        Path target = dir.resolve(name);

        if (Files.exists(source) && !Files.exists(target)) {
            Files.copy(source, target);
        }
    }
}
```

### 逐段详解

#### 1. 复制配置

```
Path sourceConfig = repoRoot.resolve(".rook/config.yaml");
if (Files.exists(sourceConfig)) {
    Path targetConfig = dir.resolve(".rook/config.yaml");
    Files.createDirectories(targetConfig.getParent());
    Files.copy(sourceConfig, targetConfig, REPLACE_EXISTING);
}
```

**子 Agent 需要知道配置：**

- LLM API key
- 权限设置
- 工具配置

配置文件复制过去，子 Agent 才能正常运行。

#### 2. 软链大目录

```
List<String> largeDirs = List.of(
    "node_modules", "target", "build", ".venv", "venv", ".gradle"
);

for (String name : largeDirs) {
    Path source = repoRoot.resolve(name);
    Path target = dir.resolve(name);
    if (Files.exists(source)) {
        Files.createSymbolicLink(target, source);
    }
}
```

**为什么软链？**

- `node_modules` 可能有几个 G
- 每个 worktree 都复制一份，磁盘爆炸
- 软链指向主仓库的目录，共享同一份
- 子 Agent 能看到依赖，但不占额外空间

**注意：**

- 软链是只读共享的，子 Agent 不应该改依赖目录
- Windows 下创建软链可能需要管理员权限，失败就跳过

#### 3. 补充忽略文件

```
List<String> ignoredFiles = List.of(".env", "local.properties", ...);

for (String name : ignoredFiles) {
    Path source = repoRoot.resolve(name);
    Path target = dir.resolve(name);
    if (Files.exists(source) && !Files.exists(target)) {
        Files.copy(source, target);
    }
}
```

**为什么要补充？**

- `.env` 等文件被 `.gitignore` 忽略，不在版本库里
- orphan worktree 是空的，没有这些文件
- 但运行时需要（数据库连接、API key 等）
- 从主仓库复制过去

------

## 第四步：变更检测与清理

```
// 在 WorktreeManager 中继续

/**
 * 自动清理：无变更删除，有变更保留
     * @return null 表示已清理，非 null 表示保留原因
     */
    public String autoCleanup(String slug) throws IOException {
        String safe = validateSlug(slug);
        Path dir = worktreeBase.resolve(toFlatSlug(safe));

        if (!Files.exists(dir)) {
            return null;  // 不存在，不用清理
        }

        // 检测是否有未提交的修改
        if (hasUncommitted(dir)) {
            return "Worktree 有未提交的修改，已保留供 review";
        }

        // 检测是否有未推送的 commit
        if (hasUnpushedCommits(dir)) {
            return "Worktree 有未推送的 commit，已保留供 review";
        }

        // 无变更，安全删除
        remove(slug, true);
        return null;
    }

    /**
     * 检测未提交的修改（包括未跟踪文件）
     */
    private boolean hasUncommitted(Path dir) throws IOException {
        String output = runGitCapture(dir, "status", "--porcelain");
        return !output.isBlank();
    }

    /**
     * 检测未推送的 commit
     */
    private boolean hasUnpushedCommits(Path dir) throws IOException {
        // 获取当前分支名
        String branch = runGitCapture(dir, "rev-parse", "--abbrev-ref", "HEAD").trim();
        if (branch.isEmpty() || "HEAD".equals(branch)) {
            return false;  //  detached HEAD 或空分支
        }

        // 检查是否有上游分支
        String upstream = runGitCapture(dir, "rev-parse", "--abbrev-ref", 
            branch + "@{upstream}");
        if (upstream.contains("fatal") || upstream.isBlank()) {
            // 没有上游分支，检查是否有 commit
            String log = runGitCapture(dir, "log", "--oneline", "-1");
            return !log.isBlank();
        }

        // 有上游，检查 ahead 数量
        String ahead = runGitCapture(dir, "rev-list", "--count", 
            "@{upstream}..HEAD");
        try {
            return Integer.parseInt(ahead.trim()) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 删除 worktree
     * @param force 是否强制删除（有改动时）
     */
    public void remove(String slug, boolean force) throws IOException {
        String safe = validateSlug(slug);
        Path dir = worktreeBase.resolve(toFlatSlug(safe));

        if (!Files.exists(dir)) return;

        if (force) {
            runGit("worktree", "remove", "--force", dir.toString());
        } else {
            runGit("worktree", "remove", dir.toString());
        }

        // 删除对应的分支
        String branch = branchName(safe);
        try {
            runGit("branch", "-D", branch);
        } catch (IOException e) {
            // 分支可能不存在，忽略
        }

        System.out.println("[Worktree] 已删除: " + dir);
    }

    /**
     * 在指定目录执行 git 命令并捕获输出
     */
    private String runGitCapture(Path worktreeDir, String... args) throws IOException {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(worktreeDir.toString());
        cmd.addAll(List.of(args));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        try {
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor(15, TimeUnit.SECONDS);
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git 命令被中断", e);
        }
    }
}
```

### 逐段详解

#### 1. autoCleanup — 智能清理

```
public String autoCleanup(String slug) {
    if (hasUncommitted(dir)) {
        return "Worktree 有未提交的修改，已保留供 review";
    }
    if (hasUnpushedCommits(dir)) {
        return "Worktree 有未推送的 commit，已保留供 review";
    }
    remove(slug, true);
    return null;
}
```

**★ 变更保护的核心。**

**逻辑：**

1. 有未提交修改 → 保留，不删
2. 有未推送 commit → 保留，不删
3. 都没有 → 安全删除

**为什么有变更就保留？**

- 子 Agent 的改动可能有价值
- 自动删了就找不回来了
- 保留下来，用户可以 review 后决定要不要合并
- 没改动的 worktree 就是垃圾，自动清理

#### 2. hasUncommitted — 检测未提交修改

```
private boolean hasUncommitted(Path dir) {
    String output = runGitCapture(dir, "status", "--porcelain");
    return !output.isBlank();
}
```

**`git status --porcelain`：**

- 机器可读的格式
- 有输出就说明有改动（包括未跟踪文件）
- 空输出说明工作区干净

#### 3. hasUnpushedCommits — 检测未推送 commit

```
private boolean hasUnpushedCommits(Path dir) {
    // 1. 获取当前分支
    // 2. 检查是否有上游分支
    // 3. 有上游：检查 ahead 数量
    // 4. 没上游：检查是否有 commit
}
```

**两种情况算 "未推送"：**

- 有上游分支，但本地比上游多 commit（ahead > 0）
- 没有上游分支（新建的分支），但有 commit

**子 Agent 的 orphan 分支一般没有上游，所以有 commit 就算未推送。**

#### 4. remove — 删除 worktree

```
public void remove(String slug, boolean force) {
    if (force) {
        runGit("worktree", "remove", "--force", dir.toString());
    } else {
        runGit("worktree", "remove", dir.toString());
    }
    // 同时删除分支
    runGit("branch", "-D", branch);
}
```

**删除分两步：**

1. `git worktree remove` 删除工作目录
2. `git branch -D` 删除对应的分支

**不删分支的话，分支还在，下次创建同名 worktree 会冲突。**

------

## 第五步：过期清理

```
// 在 WorktreeManager 中继续

import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 清理过期的临时 worktree（超过 30 分钟）
 * @return 清理的数量
 */
public int cleanStale() throws IOException {
    if (!Files.exists(worktreeBase)) return 0;

    int cleaned = 0;
    Instant threshold = Instant.now().minus(30, ChronoUnit.MINUTES);

    try (DirectoryStream<Path> stream = Files.newDirectoryStream(worktreeBase)) {
        for (Path dir : stream) {
            if (!Files.isDirectory(dir)) continue;
            if (!Files.exists(dir.resolve(".git"))) continue;

            // 检查最后修改时间
            BasicFileAttributes attrs = Files.readAttributes(dir, 
                BasicFileAttributes.class);
            Instant lastModified = attrs.lastModifiedTime().toInstant();

            if (lastModified.isBefore(threshold)) {
                // 超过 30 分钟，检查是否有变更
                String slug = fromFlatSlug(dir.getFileName().toString());
                if (!hasUncommitted(dir) && !hasUnpushedCommits(dir)) {
                    // 无变更，强制删除
                    try {
                        remove(slug, true);
                        cleaned++;
                    } catch (IOException e) {
                        System.err.println("[Worktree] 清理失败: " + dir 
                            + " - " + e.getMessage());
                    }
                }
            }
        }
    }

    if (cleaned > 0) {
        System.out.println("[Worktree] 清理了 " + cleaned + " 个过期 worktree");
    }
    return cleaned;
}

/**
 * flat slug 转回原始 slug
 * agent+explore → agent/explore
 */
private String fromFlatSlug(String flat) {
    return flat.replace("+", "/");
}
```

### 逐段详解

#### 1. 30 分钟过期

```
Instant threshold = Instant.now().minus(30, ChronoUnit.MINUTES);
```

**为什么 30 分钟？**

- 子 Agent 任务一般几分钟到十几分钟
- 超过 30 分钟没动静，大概率是崩了或被遗弃了
- 30 分钟比较合理，不会误删正在运行的

#### 2. 有变更不删

```
if (!hasUncommitted(dir) && !hasUnpushedCommits(dir)) {
    remove(slug, true);
}
```

**即使过期了，有变更也不删。**

- 用户的工作成果不能因为过期就丢了
- 只清理无变更的垃圾 worktree

#### 3. 定时调用

**在哪里调用 cleanStale？**

- 程序启动时
- 每次创建新 worktree 前
- 或者定时任务（每小时一次）

```
// 启动时清理
worktreeManager.cleanStale();

// 每次创建前清理
public Path create(String slug) {
    cleanStale();  // 顺手清理
    // ...
}
```

------

## 第六步：集成到子 Agent

### 角色定义加 isolation 字段

```
---
name: refactor
description: "代码重构Agent，在隔离环境中修改代码"
tools: [read_file, write_file, edit_file, exec_command, glob, grep]
disallowedTools: [agent]
maxRounds: 20
isolation: worktree
permissionMode: accept_edits
---

你是代码重构专家。在隔离的 worktree 中进行重构，不影响主工作区。
...
```

### SubAgentRunner 中检测 isolation

```
// subagent/SubAgentRunner.java

public SubAgentResult run(...) {
    // ... 之前的准备工作 ...

    // ★ Worktree 隔离
    Path worktreePath = null;
    if ("defined".equals(mode) && def != null 
            && "worktree".equals(def.isolation())) {
        try {
            worktreePath = worktreeManager.create("agent-" + def.name());
            
            // 告诉子 Agent 工作目录在哪里
            history.add(new ChatMessage("system", 
                "你的工作目录是: " + worktreePath.toAbsolutePath()
                + "\n所有文件操作都在这个目录下进行。"
                + "\n不要尝试访问主工作区的文件。"));

            // 子 Agent 的工具都切换到 worktree 目录
            // （需要工具支持配置工作目录）
            subTools.setWorkingDir(worktreePath);

        } catch (IOException e) {
            throw new RuntimeException("创建 worktree 失败: " + e.getMessage(), e);
        }
    }

    // ... 运行 AgentLoop ...

    // ★ 完成后自动清理
    if (worktreePath != null) {
        try {
            String reason = worktreeManager.autoCleanup("agent-" + def.name());
            if (reason != null) {
                // 有变更，保留，在结果里说明
                resultBuilder.worktreeNote(reason);
                resultBuilder.worktreePath(worktreePath);
            }
        } catch (IOException e) {
            System.err.println("[SubAgent] worktree 清理失败: " + e.getMessage());
        }
    }

    return result;
}
```

### 工具切换工作目录

**文件工具需要支持配置工作目录：**

```
// tools/impl/ReadFileTool.java
public class ReadFileTool implements Tool {
    private Path workingDir;

    public void setWorkingDir(Path dir) {
        this.workingDir = dir;
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String path = (String) params.get("path");
        Path target = workingDir != null ? workingDir.resolve(path) : Paths.get(path);
        // ... 读取文件 ...
    }
}
```

**所有文件工具（read/write/edit/glob/grep）都要支持 setWorkingDir。**

------

## 完整流程

### 子 Agent + Worktree 完整生命周期

```
主 Agent 派 refactor 子 Agent（isolation: worktree）
    │
    ▼
SubAgentRunner 检测到 isolation: worktree
    │
    ▼
WorktreeManager.create("agent-refactor")
    ├─ 校验 slug
    ├─ 计算路径: .rook/worktrees/agent-refactor/
    ├─ 已存在？→ 恢复
    ├─ 不存在：
    │   ├─ git worktree add --orphan rook/agent-refactor
    │   ├─ 复制配置（.rook/config.yaml）
    │   ├─ 软链大目录（node_modules, target...）
    │   └─ 补充忽略文件（.env...）
    └─ 返回 worktree 路径
    │
    ▼
子 Agent 启动
    ├─ system 消息："工作目录是 xxx，所有操作在此目录"
    ├─ 工具切换到 worktree 目录
    └─ 开始执行任务（改代码、跑测试...）
    │
    ▼
子 Agent 完成
    │
    ▼
WorktreeManager.autoCleanup("agent-refactor")
    ├─ 有未提交修改？
    │   ├─ 是 → 保留，返回"有变更，已保留供 review"
    │   └─ 否 → 继续
    ├─ 有未推送 commit？
    │   ├─ 是 → 保留
    │   └─ 否 → 继续
    └─ 无变更 → git worktree remove --force + git branch -D
    │
    ▼
返回结果给主 Agent
    ├─ 无变更："子 Agent 完成，worktree 已自动清理"
    └─ 有变更："子 Agent 完成，worktree 有变更，已保留在 xxx"
```

------

## 运行验证

### 测试 1：创建 worktree

```
> 派一个 refactor Agent 重构 UserService（隔离环境）

[调用工具: agent(subagent_type="refactor", prompt="重构 UserService")]

[Worktree] 已创建: /project/.rook/worktrees/agent-refactor 
  (分支: rook/agent-refactor)
[SubAgent] 工作目录: /project/.rook/worktrees/agent-refactor

（子 Agent 在隔离目录中重构代码）

[SubAgent] 完成
[Worktree] 有未提交的修改，已保留供 review

结果：
- 子 Agent 输出: 重构完成，提取了 UserRepository 接口...
- Worktree: /project/.rook/worktrees/agent-refactor（有变更，已保留）
- 分支: rook/agent-refactor
```

### 测试 2：无变更自动清理

```
> 派一个 explore Agent 看看项目结构（只读）

[调用工具: agent(subagent_type="explore", ...)]

[Worktree] 已创建: .../agent-explore

（子 Agent 只读探索，没改文件）

[SubAgent] 完成
[Worktree] 已删除: .../agent-explore

结果：
- 子 Agent 输出: 项目结构...
- Worktree: 已自动清理（无变更）
```

### 测试 3：恢复已存在的 worktree

```
> （上次的 refactor worktree 还在）
> 继续派 refactor Agent

[Worktree] 恢复已存在的: .../agent-refactor

（子 Agent 在之前的 worktree 基础上继续）
```

### 测试 4：查看 worktree 列表

```
$ git worktree list
/project                          abc1234 [main]
/project/.rook/worktrees/agent-refactor  def5678 [rook/agent-refactor]
```

### 测试 5：手动合并子 Agent 的改动

```
$ cd /project
$ git merge rook/agent-refactor
（或者 cherry-pick）
```

------

## 常见坑（详细版）

### 1. 不是 Git 仓库

**现象：** 创建 worktree 报错 "not a git repository"。

**原因：** 项目目录没有 `git init`。

**解决：**

- 使用前检查是否是 git 仓库

```
if (!Files.exists(repoRoot.resolve(".git"))) {
    throw new IllegalStateException("不是 Git 仓库，无法使用 worktree 隔离");
}
```

- 不是 git 仓库的项目，子 Agent 只能用共享目录模式

### 2. Windows 路径问题

**现象：** flatSlug 里的 `/` 在 Windows 下被当路径分隔符。

**原因：** Windows 下 `/` 和 `\` 都是路径分隔符。

**解决：**

- toFlatSlug 把 `/` 换成 `+`
- 目录名变成扁平的，不会有子目录
- `agent/explore` → `agent+explore`

### 3. 软链需要管理员权限

**现象：** Windows 下创建软链报错 "需要提升权限"。

**原因：** Windows 创建符号链接默认需要管理员权限。

**解决：**

- catch 异常，软链失败就跳过
- 或者用目录联接（junction）代替软链
- 或者直接复制（占空间但兼容）
- 或者开启开发者模式（Windows 10+）

### 4. 同一个分支被多个 worktree 检出

**现象：** 创建 worktree 报错 "branch is already checked out"。

**原因：** 同一个分支不能同时被两个 worktree 检出。

**解决：**

- 每个 worktree 用唯一的分支名（`rook/<slug>`）
- slug 唯一，分支名就唯一
- 恢复已存在的 worktree 时不重新创建

### 5. 子 Agent 工具没切换目录

**现象：** 子 Agent 还是在改主工作区的文件。

**原因：** 工具的 workingDir 没设置对。

**解决：**

- 所有文件工具都要支持 setWorkingDir
- 创建子 Agent 时统一设置
- exec_command 也要切换工作目录（ProcessBuilder.directory ()）

### 6. 大目录软链后子 Agent 修改了依赖

**现象：** 子 Agent 改了 node_modules 里的文件，影响主仓库。

**原因：** 软链是共享的，两边都能改。

**解决：**

- 约定子 Agent 不修改依赖目录
- 或者软链设为只读（Windows 下麻烦）
- 或者不软链，直接复制（占空间但安全）
- 一般子 Agent 不会改依赖，问题不大

### 7. 过期清理误删正在运行的

**现象：** 子 Agent 还在跑，worktree 被清理了。

**原因：** 子 Agent 运行时间超过 30 分钟，或者最后修改时间没更新。

**解决：**

- 30 分钟是保守值，一般子 Agent 不会跑那么久
- 清理前检查是否有进程在使用（复杂，暂时不做）
- 或者清理时只删无变更的，正在运行的一般有变更
- 或者延长到 1 小时

### 8. orphan 分支没有历史

**现象：** 子 Agent 在 worktree 里看不到主分支的代码。

**原因：** `--orphan` 创建的是空分支，工作区是空的。

**解决：**

- 这是设计如此，子 Agent 从零开始
- 如果需要主分支的代码，不用 --orphan，用普通 worktree
- 或者子 Agent 自己 `git checkout main -- <path>` 拿需要的文件
- 探索类任务用 orphan 正好（干净），重构类可能需要基础代码

### 9. .gitignore 忽略的文件没复制全

**现象：** 子 Agent 运行时缺文件（比如配置文件）。

**原因：** supplementIgnoredFiles 里的列表不全。

**解决：**

- 维护一个常见的忽略文件列表
- 或者让用户在配置里指定需要复制的文件
- 或者扫描 .gitignore，自动复制被忽略的文件（复杂）

### 10. worktree 删了但分支还在

**现象：** `git worktree list` 没有了，但 `git branch` 还有 rook/agent-xxx。

**原因：** 删除 worktree 时没删分支，或者删分支失败了。

**解决：**

- remove 方法里同时删分支
- 定期 `git branch -D` 清理 rook/ 开头的孤儿分支
- 或者 `git worktree prune` 清理失效引用

------

## 架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                        主 Git 仓库                                │
│                                                                 │
│  /project/                                                      │
│  ├── .git/                      ← 共享版本库（只有一份）         │
│  │   ├── objects/               ← 所有 commit 对象              │
│  │   ├── refs/                  ← 所有分支引用                  │
│  │   └── worktrees/             ← worktree 元数据               │
│  │       ├── agent-refactor/                                    │
│  │       └── agent-explore/                                     │
│  │                                                              │
│  ├── src/                       ← 主 Agent 工作区               │
│  ├── .rook/                                                     │
│  │   ├── config.yaml                                            │
│  │   └── worktrees/             ← 子 Agent 工作区               │
│  │       ├── agent-refactor/                                    │
│  │       │   ├── .git           ← 文件，指向主仓库              │
│  │       │   ├── src/           ← 独立的文件                    │
│  │       │   ├── .rook/         ← 复制的配置                    │
│  │       │   ├── node_modules → 软链到主仓库                    │
│  │       │   └── .env           ← 复制的忽略文件                │
│  │       └── agent-explore/                                     │
│  │           ├── .git                                           │
│  │           └── ...                                            │
│  └── ...                                                        │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                       WorktreeManager                            │
│                                                                 │
│  create(slug):                                                  │
│    validateSlug() → 安全校验                                     │
│    已存在？→ 恢复                                                │
│    不存在：                                                      │
│      git worktree add --orphan rook/<slug> <dir>                │
│      initEnv(dir):                                              │
│        ├─ copyConfig()    复制 .rook 配置                       │
│        ├─ symlinkLargeDirs() 软链 node_modules 等               │
│        └─ supplementIgnoredFiles() 复制 .env 等                 │
│                                                                 │
│  autoCleanup(slug):                                             │
│    hasUncommitted()? → 保留                                     │
│    hasUnpushedCommits()? → 保留                                 │
│    都没有 → remove(slug, force=true)                            │
│                                                                 │
│  cleanStale():                                                  │
│    遍历 .rook/worktrees/                                        │
│    超过 30 分钟 + 无变更 → 删除                                  │
│                                                                 │
│  remove(slug, force):                                           │
│    git worktree remove [--force] <dir>                          │
│    git branch -D rook/<slug>                                    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                        子 Agent 集成                              │
│                                                                 │
│  agent.md:                                                      │
│    isolation: worktree                                          │
│                                                                 │
│  SubAgentRunner.run():                                          │
│    if def.isolation == "worktree":                              │
│      worktreePath = worktreeManager.create("agent-" + name)    │
│      history.add(system: "工作目录: xxx")                       │
│      tools.setWorkingDir(worktreePath)                          │
│    ...                                                          │
│    完成后:                                                      │
│      reason = worktreeManager.autoCleanup(...)                  │
│      reason != null → 结果里说明"有变更，已保留"                │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

------

## 隔离方式对比

|   维度   |    共享目录    |    Worktree 隔离    |      完整 Clone      |
| :------: | :------------: | :-----------------: | :------------------: |
|  隔离性  | 差（互相覆盖） |  好（独立工作区）   |   最好（完全独立）   |
| 空间占用 |     无额外     |  小（共享版本库）   |    大（完整复制）    |
| 创建速度 |      即时      |     快（几秒）      |   慢（可能几分钟）   |
| 版本共享 |       -        | 共享（commit 可见） | 独立（要 push/pull） |
| 并行支持 |      不能      |         能          |          能          |
| 清理难度 |  手动 revert   | 自动（删 worktree） |        删目录        |
| 适用场景 |    简单只读    |   大多数子 Agent    |     需要完全独立     |

**Worktree 是性价比最高的隔离方式：** 隔离性好、空间小、速度快。

------

## 下一章

[25 - 团队协作.md](./25-团队协作.md) — 长期团队 + 共享任务 + 点对点消息 + Coordinator
