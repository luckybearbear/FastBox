package com.fastbox.gateway.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fastbox.gateway.util.FastBoxPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Java 插件加载器
 *
 * <p>支持两种执行模式：
 * <ul>
 *   <li><b>ClassLoader 模式（默认）</b>：在网关 JVM 内用独立 URLClassLoader 加载插件 jar，延迟最低。
 *   <li><b>Process 模式（sandbox="process"）</b>：fork 独立 JVM 子进程执行插件，异常插件不会拖垮网关。
 * </ul>
 *
 * <p>两种模式都通过反射调用统一 SPI 方法 {@code run(String, List, Map)}，返回统一 JSON 契约。
 *
 * <p>热重载：ClassLoader 模式缓存 ClassLoader，jar 变更后自动重建；Process 模式每次启动新子进程，天然支持热重载。
 *
 * <p>异常隔离：插件抛出的任何异常都被捕获并转换为 {@code {code, message}}，不向上传播。
 */
public class JavaPluginLoader {

    private static final Logger log = LoggerFactory.getLogger(JavaPluginLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long PROCESS_TIMEOUT_SECONDS = 30;

    /** loader 缓存：pluginPath -> (jarModified, loader) */
    private final Map<String, LoaderEntry> cache = new ConcurrentHashMap<>();

    /**
     * 执行 Java 插件
     *
     * @param pluginDir 插件目录（t_plugin.path，含 plugin.json 与 jar）
     * @param keyword   触发关键词
     * @param args      用户参数
     * @return 统一 JSON 契约 {code, data, message}
     */
    public Map<String, Object> execute(Path pluginDir, String keyword, List<String> args) {
        try {
            JsonNode meta = MAPPER.readTree(pluginDir.resolve("plugin.json").toFile());
            String mainClass = meta.path("main").asText("");
            if (mainClass.isBlank()) {
                return Map.of("code", 1, "message", "plugin.json 缺少 main 字段");
            }
            Path jar = findJar(pluginDir);
            if (jar == null) {
                return Map.of("code", 1, "message", "插件目录未找到 .jar 文件: " + pluginDir);
            }
            boolean useProcess = "process".equalsIgnoreCase(meta.path("sandbox").asText(""));
            if (useProcess) {
                return executeProcess(pluginDir, jar, mainClass, keyword, args);
            }
            return executeLoader(pluginDir, jar, mainClass, keyword, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("Java 插件执行异常: {}", cause.getMessage());
            return Map.of("code", 1, "message", "插件执行异常: " + cause.getMessage());
        } catch (Exception e) {
            log.warn("Java 插件加载失败: {}", e.getMessage());
            return Map.of("code", 1, "message", "插件加载失败: " + e.getMessage());
        }
    }

    /** 关闭所有缓存的 loader（网关关闭时调用） */
    public void close() {
        cache.values().forEach(LoaderEntry::close);
        cache.clear();
    }

    private Map<String, Object> executeLoader(Path pluginDir, Path jar, String mainClass, String keyword, List<String> args) throws Exception {
        LoaderEntry entry = getLoader(pluginDir, jar);
        Object instance = entry.mainClass.getDeclaredConstructor().newInstance();
        Method run = entry.mainClass.getMethod("run", String.class, List.class, Map.class);
        Object result = run.invoke(instance, keyword, args == null ? List.of() : args, Map.of());
        if (result instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> out = (Map<String, Object>) m;
            return out;
        }
        return Map.of("code", 1, "message", "插件返回值类型错误: " + (result == null ? "null" : result.getClass().getName()));
    }

    /**
     * 子进程隔离执行：启动独立 JVM 加载插件，通过 stdout 读取 JSON 结果。
     *
     * <p>子进程 classpath 使用网关 jar 承载 PluginLauncher 与 Jackson，
     * 插件 jar 提供 SPI 接口与实现类。子进程异常不会影响网关主进程。
     *
     * <p>用户参数通过临时 JSON 文件传递（--args-file），避免 Windows 命令行引号/空格转义问题。
     */
    private Map<String, Object> executeProcess(Path pluginDir, Path jar, String mainClass, String keyword, List<String> args) throws Exception {
        Path gatewayJar = findGatewayJar();
        if (gatewayJar == null) {
            return Map.of("code", 1, "message", "无法定位网关 jar，子进程模式不可用");
        }
        Path tmpDir = FastBoxPaths.dataDir().resolve("tmp");
        Files.createDirectories(tmpDir);
        Path argsFile = tmpDir.resolve("plugin-args-" + System.nanoTime() + ".json");
        try {
            Files.writeString(argsFile, MAPPER.writeValueAsString(args == null ? List.of() : args), StandardCharsets.UTF_8);
            String classpath = gatewayJar.toAbsolutePath() + File.pathSeparator + jar.toAbsolutePath();
            List<String> cmd = List.of(
                    "java",
                    "-cp", classpath,
                    "com.fastbox.plugin.launcher.PluginLauncher",
                    "--jar", jar.toAbsolutePath().toString(),
                    "--main", mainClass,
                    "--keyword", keyword == null ? "" : keyword,
                    "--args-file", argsFile.toAbsolutePath().toString()
            );
            log.info("[java-plugin] 启动子进程执行插件: {}", pluginDir.getFileName());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            try {
                boolean finished = proc.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!finished) {
                    proc.destroyForcibly();
                    log.warn("[java-plugin] 子进程执行超时，已强制终止: {}", pluginDir.getFileName());
                    return Map.of("code", 1, "message", "插件执行超时（超过 " + PROCESS_TIMEOUT_SECONDS + " 秒）");
                }
                String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                log.info("[java-plugin] 子进程输出: {}", out.isEmpty() ? "(空)" : out.substring(0, Math.min(out.length(), 200)));
                if (out.isBlank()) {
                    return Map.of("code", 1, "message", "插件子进程无输出");
                }
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = MAPPER.readValue(out, Map.class);
                    return result;
                } catch (Exception parseErr) {
                    return Map.of("code", 1, "message", "子进程输出不是有效 JSON: " + parseErr.getMessage() + " | 原始输出: " + out.substring(0, Math.min(out.length(), 200)));
                }
            } finally {
                try {
                    Files.deleteIfExists(argsFile);
                } catch (Exception ignored) {
                }
                if (proc.isAlive()) {
                    proc.destroyForcibly();
                }
            }
        } finally {
            try {
                Files.deleteIfExists(argsFile);
            } catch (Exception ignored) {
            }
        }
    }

    private LoaderEntry getLoader(Path pluginDir, Path jar) throws Exception {
        long modified = Files.getLastModifiedTime(jar).to(TimeUnit.MILLISECONDS);
        LoaderEntry entry = cache.get(pluginDir.toString());
        if (entry != null && entry.jarModified == modified) {
            return entry;
        }
        // 重建 loader
        if (entry != null) {
            entry.close();
        }
        URLClassLoader loader = new URLClassLoader(
                new URL[]{jar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader()); // 平台类加载器：不暴露网关内部类
        String metaMain = MAPPER.readTree(pluginDir.resolve("plugin.json").toFile()).path("main").asText("");
        Class<?> mainClass = Class.forName(metaMain, true, loader);
        LoaderEntry fresh = new LoaderEntry(modified, loader, mainClass);
        cache.put(pluginDir.toString(), fresh);
        log.info("[java-plugin] 已加载 {} (jar: {})", metaMain, jar.getFileName());
        return fresh;
    }

    private Path findJar(Path pluginDir) throws Exception {
        try (var stream = Files.list(pluginDir)) {
            return stream.filter(p -> p.toString().toLowerCase().endsWith(".jar"))
                    .findFirst().orElse(null);
        }
    }

    private Path findGatewayJar() {
        try {
            // 开发/生产：FastBox/java-gateway/target/fastbox-gateway-0.1.0-shaded.jar
            java.net.URI uri = JavaPluginLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path path = Path.of(uri);
            Path shaded = path.getParent().resolve("fastbox-gateway-0.1.0-shaded.jar");
            if (Files.isRegularFile(shaded)) return shaded;
            Path plain = path.getParent().resolve("fastbox-gateway.jar");
            if (Files.isRegularFile(plain)) return plain;
        } catch (Exception e) {
            log.warn("定位网关 jar 失败: {}", e.getMessage());
        }
        return null;
    }

    /** 缓存条目 */
    private static final class LoaderEntry implements Closeable {
        final long jarModified;
        final URLClassLoader loader;
        final Class<?> mainClass;

        LoaderEntry(long jarModified, URLClassLoader loader, Class<?> mainClass) {
            this.jarModified = jarModified;
            this.loader = loader;
            this.mainClass = mainClass;
        }

        @Override
        public void close() {
            try {
                loader.close();
            } catch (Exception ignored) {
            }
        }
    }
}
