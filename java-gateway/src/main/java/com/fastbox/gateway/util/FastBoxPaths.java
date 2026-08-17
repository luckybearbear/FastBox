package com.fastbox.gateway.util;

import java.net.URI;
import java.nio.file.Path;

/**
 * 项目路径解析工具
 *
 * 无论从哪个工作目录启动，都基于 jar 所在位置推导 FastBox 根目录：
 *   FastBox/java-gateway/target/fastbox-gateway.jar
 *     → target → java-gateway → FastBox（根）
 *
 * 可用环境变量覆盖：
 *   FASTBOX_HOME      项目根目录
 *   FASTBOX_DATA_DIR  数据目录（默认 <root>/data）
 */
public class FastBoxPaths {

    private FastBoxPaths() {
    }

    public static Path projectRoot() {
        String home = System.getenv("FASTBOX_HOME");
        if (home != null && !home.isBlank()) {
            return Path.of(home);
        }
        try {
            // 生产: FastBox/java-gateway/target/fastbox-gateway.jar
            // 开发: FastBox/java-gateway/target/classes/
            // 两种情况都上跳 3 级 → FastBox 根
            URI uri = FastBoxPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            return Path.of(uri).getParent().getParent().getParent();
        } catch (Exception e) {
            return Path.of(System.getProperty("user.dir"));
        }
    }

    public static Path dataDir() {
        String dir = System.getenv("FASTBOX_DATA_DIR");
        if (dir != null && !dir.isBlank()) {
            return Path.of(dir);
        }
        return projectRoot().resolve("data");
    }

    public static Path pythonPlugins() {
        return projectRoot().resolve("python-runtime/plugins");
    }

    public static Path jsPlugins() {
        return projectRoot().resolve("plugins/js");
    }

    public static Path javaPlugins() {
        return projectRoot().resolve("plugins/java");
    }

    public static Path sqlScripts() {
        return projectRoot().resolve("scripts/sql");
    }
}
