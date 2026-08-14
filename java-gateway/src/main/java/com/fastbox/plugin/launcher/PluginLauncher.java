package com.fastbox.plugin.launcher;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Java 插件子进程启动器
 *
 * <p>独立 JVM 入口，用于沙箱隔离执行 Java 插件。通过命令行接收参数：
 * <pre>
 *   java -cp ... com.fastbox.plugin.launcher.PluginLauncher
 *       --jar          /path/to/plugin.jar
 *       --main         com.example.MyPlugin
 *       --keyword      the-keyword
 *       --args         '["arg1","arg2"]'
 *       --args-file    /path/to/args.json
 * </pre>
 *
 * <p>参数说明：
 * <ul>
 *   <li>--args 直接传入 JSON 数组（命令行环境必须保证整串作为一个参数）。</li>
 *   <li>--args-file 从文件读取 JSON 数组，推荐用于避免引号/空格转义问题。</li>
 * </ul>
 *
 * <p>输出统一 JSON 契约到标准输出：
 * <pre>
 *   { "code": 0, "data": {...}, "message": "ok" }
 * </pre>
 *
 * <p>所有异常都被捕获并输出为 code≠0 的 JSON，子进程不会因为插件异常而异常退出。
 */
public class PluginLauncher {

    public static void main(String[] args) {
        // 强制标准输出使用 UTF-8，避免 Windows 默认 GBK 导致网关解析乱码
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        try {
            String jarPath = null;
            String mainClass = null;
            String keyword = "";
            String argsJson = null;
            String argsFile = null;

            for (int i = 0; i < args.length; i++) {
                String key = args[i];
                if (key.startsWith("--") && i + 1 >= args.length) {
                    throw new IllegalArgumentException("缺少 " + key + " 的值");
                }
                switch (key) {
                    case "--jar" -> jarPath = args[++i];
                    case "--main" -> mainClass = args[++i];
                    case "--keyword" -> keyword = args[++i];
                    case "--args" -> argsJson = args[++i];
                    case "--args-file" -> argsFile = args[++i];
                }
            }

            if (jarPath == null || jarPath.isBlank()) {
                throw new IllegalArgumentException("缺少 --jar");
            }
            if (mainClass == null || mainClass.isBlank()) {
                throw new IllegalArgumentException("缺少 --main");
            }

            ObjectMapper mapper = new ObjectMapper();
            List<String> userArgs;
            if (argsFile != null && !argsFile.isBlank()) {
                userArgs = mapper.readValue(Files.readString(Path.of(argsFile), StandardCharsets.UTF_8), List.class);
            } else if (argsJson != null && !argsJson.isBlank()) {
                userArgs = mapper.readValue(argsJson, List.class);
            } else {
                userArgs = List.of();
            }

            File jarFile = new File(jarPath);
            if (!jarFile.isFile()) {
                throw new IllegalArgumentException("jar 文件不存在: " + jarPath);
            }

            URLClassLoader loader = new URLClassLoader(
                    new URL[]{jarFile.toURI().toURL()},
                    ClassLoader.getPlatformClassLoader());
            Class<?> clazz = Class.forName(mainClass, true, loader);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            Method run = clazz.getMethod("run", String.class, List.class, Map.class);
            Object result = run.invoke(instance, keyword, userArgs, Map.of());

            if (result instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> out = (Map<String, Object>) m;
                System.out.println(mapper.writeValueAsString(out));
            } else {
                System.out.println(mapper.writeValueAsString(
                        Map.of("code", 1, "message", "插件返回值类型错误: " + (result == null ? "null" : result.getClass().getName()))));
            }
        } catch (Exception e) {
            Throwable cause = e;
            if (e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null) {
                cause = e.getCause();
            }
            try {
                ObjectMapper mapper = new ObjectMapper();
                System.out.println(mapper.writeValueAsString(
                        Map.of("code", 1, "message", cause.getMessage() == null ? cause.getClass().getName() : cause.getMessage())));
            } catch (Exception ignored) {
                System.err.println("PluginLauncher 严重错误: " + cause.getMessage());
                cause.printStackTrace();
            }
        }
    }
}
