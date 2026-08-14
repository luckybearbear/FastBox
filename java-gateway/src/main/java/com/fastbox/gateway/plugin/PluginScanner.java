package com.fastbox.gateway.plugin;

import com.fastbox.gateway.db.Database;
import com.fastbox.gateway.util.FastBoxPaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.stream.Stream;

/**
 * 插件注册中心 — 启动时扫描插件目录，自动注册到 t_plugin
 *
 * 扫描路径（基于 FastBox 根目录，与启动位置无关）：
 *   python-runtime/plugins/<name>/plugin.json  → kind=python
 *   plugins/js/<name>/plugin.json              → kind=js
 *   plugins/java/<name>/plugin.json            → kind=java（入口类由 main 字段声明）
 */
public class PluginScanner {

    private static final Logger log = LoggerFactory.getLogger(PluginScanner.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path projectRoot;

    public PluginScanner() {
        this.projectRoot = FastBoxPaths.projectRoot();
    }

    /** 启动时执行：扫描并注册（幂等，已存在则更新关键词） */
    public int scan() {
        int registered = 0;
        registered += scanPython();
        registered += scanJs();
        registered += scanJava();
        return registered;
    }

    private int scanPython() {
        Path base = projectRoot.resolve("python-runtime/plugins");
        return scanDir(base, "python", "plugin.json");
    }

    private int scanJs() {
        Path base = projectRoot.resolve("plugins/js");
        return scanDir(base, "js", "plugin.json");
    }

    private int scanJava() {
        Path base = projectRoot.resolve("plugins/java");
        return scanDir(base, "java", "plugin.json");
    }

    private int scanDir(Path base, String kind, String metaFile) {
        if (!Files.isDirectory(base)) return 0;
        int count = 0;
        try (Stream<Path> dirs = Files.list(base)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                Path meta = dir.resolve(metaFile);
                if (!Files.isRegularFile(meta)) continue;
                try {
                    JsonNode node = mapper.readTree(meta.toFile());
                    String name = node.path("name").asText(dir.getFileName().toString());
                    String keywords = joinKeywords(node.path("keywords"));
                    String description = node.path("description").asText("");
                    String argsSchema = node.path("args_schema").isArray()
                            ? node.path("args_schema").toString() : "[]";
                    String pluginPath = dir.toAbsolutePath().toString();
                    if (upsertPlugin(name, kind, pluginPath, keywords, argsSchema)) count++;
                    log.info("[plugin] {} {} 关键词: {} 参数: {}", kind, name, keywords, argsSchema);
                } catch (Exception e) {
                    log.warn("[plugin] 解析失败 {}: {}", meta, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[plugin] 扫描目录失败 {}: {}", base, e.getMessage());
        }
        return count;
    }

    private String joinKeywords(JsonNode arr) {
        if (arr == null) return "";
        // 数组格式：["关键词1", "关键词2"]
        if (arr.isArray()) {
            StringBuilder sb = new StringBuilder();
            arr.forEach(n -> {
                if (!n.asText().isBlank()) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(n.asText());
                }
            });
            return sb.toString();
        }
        // 字符串格式：逗号分隔
        String text = arr.asText("");
        return text == null || text.isBlank() ? "" : text.trim();
    }

    /** 插件数量上限（防御：防止插件目录被批量灌入导致注册表膨胀） */
    private static final int MAX_PLUGINS = 200;

    /** 幂等注册：name+kind 唯一，存在则更新 keywords / args_schema */
    private boolean upsertPlugin(String name, String kind, String path, String keywords, String argsSchema) throws Exception {
        try (Connection conn = Database.getConnection();
             PreparedStatement check = conn.prepareStatement(
                     "SELECT id FROM t_plugin WHERE name = ? AND kind = ?")) {
            check.setString(1, name);
            check.setString(2, kind);
            try (ResultSet rs = check.executeQuery()) {
                if (rs.next()) {
                    try (PreparedStatement upd = conn.prepareStatement(
                            "UPDATE t_plugin SET path = ?, keywords = ?, args_schema = ? WHERE id = ?")) {
                        upd.setString(1, path);
                        upd.setString(2, keywords);
                        upd.setString(3, argsSchema);
                        upd.setInt(4, rs.getInt("id"));
                        upd.executeUpdate();
                    }
                    return false;
                }
                // 新增前检查上限（已存在的更新不受影响）
                try (PreparedStatement cnt = conn.prepareStatement("SELECT COUNT(*) FROM t_plugin");
                     ResultSet crs = cnt.executeQuery()) {
                    if (crs.next() && crs.getInt(1) >= MAX_PLUGINS) {
                        log.warn("[plugin] 插件数量已达上限 {}，跳过注册: {} ({})", MAX_PLUGINS, name, kind);
                        return false;
                    }
                }
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO t_plugin(name, kind, path, keywords, args_schema) VALUES(?, ?, ?, ?, ?)")) {
                    ins.setString(1, name);
                    ins.setString(2, kind);
                    ins.setString(3, path);
                    ins.setString(4, keywords);
                    ins.setString(5, argsSchema);
                    ins.executeUpdate();
                    return true;
                }
            }
        }
    }
}
