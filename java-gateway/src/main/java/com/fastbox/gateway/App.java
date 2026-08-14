package com.fastbox.gateway;

import com.fastbox.gateway.db.Database;
import com.fastbox.gateway.plugin.PluginScanner;
import com.fastbox.gateway.server.ApiRoutes;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FastBox Java 网关入口
 *
 * 启动流程：
 * 1. 初始化 SQLite（建表）
 * 2. 启动 Javalin HTTP 服务（默认 8764）
 */
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static final int DEFAULT_PORT = 8764;

    public static void main(String[] args) throws Exception {
        int port = parsePort(args);

        log.info("===== FastBox Gateway v0.1.0 =====");
        Database.init();
        log.info("SQLite 初始化完成: {}", Database.DB_PATH);

        int plugins = new PluginScanner().scan();
        log.info("插件扫描完成: 注册/更新 {} 个插件", plugins);

        Javalin app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.http.defaultContentType = "application/json";
        });

        ApiRoutes.register(app);

        app.start("127.0.0.1", port);
        log.info("网关已启动: http://127.0.0.1:{}", port);
        log.info("健康检查: GET /health");
    }

    private static int parsePort(String[] args) {
        for (String a : args) {
            if (a.startsWith("--port=")) {
                try {
                    return Integer.parseInt(a.substring("--port=".length()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return DEFAULT_PORT;
    }
}
