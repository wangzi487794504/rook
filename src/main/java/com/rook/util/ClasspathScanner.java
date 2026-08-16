package com.rook.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Classpath 资源扫描器 —— 枚举 classpath（目录或 fat jar）内的资源，
 * 供内置 Skill / 内置 SubAgent 在打包后仍能动态发现。
 *
 * @author wangzijie
 * @since 0.1.0
 */
public final class ClasspathScanner {

    private ClasspathScanner() {}

    /**
     * 枚举 classpath 下某资源路径的直接子目录名（去重、保持发现顺序）。
     * 开发时该路径对应 target/classes 下的真实目录，打包后对应 fat jar 内的目录条目。
     *
     * @param resourcePath 资源路径，如 {@code com/rook/skills/builtin}
     * @return 直接子目录名列表
     */
    public static List<String> listChildDirs(String resourcePath) {
        Set<String> names = new LinkedHashSet<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try {
            Enumeration<URL> urls = cl.getResources(resourcePath);
            while (urls.hasMoreElements()) {
                collectChildDirs(urls.nextElement(), names);
            }
        } catch (IOException ignored) {
            // 资源枚举失败时返回空列表，调用方按「无内置」处理
        }
        return new ArrayList<>(names);
    }

    /**
     * 读取资源文本内容，资源不存在或读取失败返回 null。
     *
     * @param resourcePath 资源路径，如 {@code com/rook/skills/builtin/commit/SKILL.md}
     */
    public static String readTextOrNull(String resourcePath) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream in = cl.getResourceAsStream(resourcePath)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static void collectChildDirs(URL url, Set<String> names) {
        try {
            URLConnection conn = url.openConnection();
            if (conn instanceof JarURLConnection jarConn) {
                collectFromJar(jarConn, names);
            } else if ("file".equals(url.getProtocol())) {
                collectFromDirectory(Paths.get(url.toURI()), names);
            }
        } catch (Exception ignored) {
            // 单个 URL 解析失败不阻断整体枚举
        }
    }

    /** 从 fat jar 内的目录条目枚举直接子目录。 */
    private static void collectFromJar(JarURLConnection jarConn, Set<String> names) throws IOException {
        String prefix = jarConn.getEntryName();
        if (prefix == null) return;
        if (!prefix.endsWith("/")) prefix += "/";
        try (JarFile jar = jarConn.getJarFile()) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String entryName = entries.nextElement().getName();
                if (!entryName.startsWith(prefix)) continue;
                String sub = entryName.substring(prefix.length());
                int slash = sub.indexOf('/');
                if (slash > 0) {
                    names.add(sub.substring(0, slash));
                }
            }
        }
    }

    /** 从文件系统目录枚举直接子目录。 */
    private static void collectFromDirectory(Path dir, Set<String> names) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                if (Files.isDirectory(p)) {
                    names.add(p.getFileName().toString());
                }
            }
        }
    }
}
