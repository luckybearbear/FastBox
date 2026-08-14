package com.fastbox.gateway.service;

import com.fastbox.gateway.db.Database;
import com.fastbox.gateway.model.ResultItem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 全局搜索调度引擎
 * 输入分词 → 本地索引检索（内置工具/插件/SQL脚本/收藏）→ 权重排序
 */
public class SearchService {

    private static final String TOOL_CALC = "calc";
    private static final String TOOL_FILES = "files";
    private static final String TOOL_HELP = "help";

    public List<ResultItem> search(String q) {
        long start = System.currentTimeMillis();
        List<ResultItem> results = new ArrayList<>();
        String kw = q == null ? "" : q.trim().toLowerCase();

        if (kw.isEmpty()) {
            return results;
        }

        // 1. 内置工具匹配
        searchBuiltin(kw, results);

        // 2. SQL 脚本匹配
        searchSqlScripts(kw, results);

        // 3. 插件匹配（关键词触发）
        searchPlugins(kw, results);

        // 4. 收藏匹配
        searchFavorites(kw, results);

        // 5. 本地文件搜索（file: 前缀 或 包含路径分隔符）
        if (kw.startsWith("file:") || kw.contains("\\") || kw.contains("/")) {
            searchFiles(kw.replaceFirst("^file:", "").trim(), results);
        }

        // 6. 写执行日志（不阻塞）
        logSearch(kw, results.size(), System.currentTimeMillis() - start);

        return results;
    }

    /* ---- 内置工具 ---- */

    private void searchBuiltin(String kw, List<ResultItem> out) {
        if ("calc".startsWith(kw) || "计算".contains(kw) || kw.matches("^[\\d+\\-*/().%\\s]+$")) {
            out.add(ResultItem.of("tool", "计算器", "输入数学表达式，例如 1+2*3", TOOL_CALC, Map.of("expr", kw)));
        }
        if ("files".startsWith(kw) || "文件".contains(kw)) {
            out.add(ResultItem.of("tool", "本地文件搜索", "搜索文件：file:关键词 或直接输入路径", TOOL_FILES, Map.of("keyword", kw)));
        }
        if ("help".startsWith(kw) || "帮助".contains(kw)) {
            out.add(ResultItem.of("tool", "FastBox 帮助", "查看快捷键与使用说明", TOOL_HELP, Map.of()));
        }
    }

    /* ---- SQL 脚本 ---- */

    private void searchSqlScripts(String kw, List<ResultItem> out) {
        String sql = "SELECT id, name, description FROM t_sql_script WHERE name LIKE ? OR description LIKE ? ORDER BY name LIMIT 10";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + kw + "%");
            ps.setString(2, "%" + kw + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("scriptId", rs.getInt("id"));
                    payload.put("name", rs.getString("name"));
                    out.add(ResultItem.of("sql", "SQL: " + rs.getString("name"),
                            rs.getString("description").isBlank() ? "执行已保存的 SQL 脚本" : rs.getString("description"),
                            "sql_script", payload));
                }
            }
        } catch (Exception e) {
            // SQLite 锁冲突等异常不阻断搜索
        }
    }

    /* ---- 插件 ---- */

    private void searchPlugins(String kw, List<ResultItem> out) {
        String sql = "SELECT id, name, kind, keywords, args_schema FROM t_plugin WHERE enabled = 1";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String keywords = rs.getString("keywords").toLowerCase();
                String name = rs.getString("name");
                if (keywords.isBlank()) continue;
                // 触发词任一前缀匹配或包含匹配
                boolean hit = false;
                for (String token : keywords.split(",")) {
                    String t = token.trim().toLowerCase();
                    if (!t.isEmpty() && (t.startsWith(kw) || kw.startsWith(t) || name.toLowerCase().contains(kw))) {
                        hit = true;
                        break;
                    }
                }
                if (hit) {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("pluginId", rs.getInt("id"));
                    payload.put("kind", rs.getString("kind"));
                    payload.put("name", name);
                    payload.put("keyword", kw);
                    payload.put("argsSchema", parseArgsSchema(rs.getString("args_schema")));
                    String kindLabel = rs.getString("kind").equals("python") ? "Python 脚本插件" : rs.getString("kind") + " 插件";
                    out.add(ResultItem.of(rs.getString("kind"), name, subtitleFor(payload, kindLabel), "plugin", payload));
                }
            }
        } catch (Exception e) {
            // ignore
        }
    }

    /** 解析 args_schema JSON 为前端可直接渲染的结构；失败返回空列表 */
    private List<Map<String, Object>> parseArgsSchema(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JavaType type = m.getTypeFactory().constructCollectionType(List.class, Map.class);
            return m.readValue(json, type);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String subtitleFor(Map<String, Object> payload, String defaultSub) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> schema = (List<Map<String, Object>>) payload.getOrDefault("argsSchema", List.of());
        if (schema.isEmpty()) return defaultSub;
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> field : schema) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(field.get("name"));
            Object required = field.get("required");
            if (Boolean.TRUE.equals(required) || "true".equals(String.valueOf(required))) {
                sb.append("*");
            }
        }
        return "需要参数: " + sb;
    }

    /* ---- 收藏 ---- */

    private void searchFavorites(String kw, List<ResultItem> out) {
        String sql = "SELECT id, name, action, payload FROM t_favorite WHERE name LIKE ? ORDER BY created_at DESC LIMIT 5";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + kw + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("favoriteId", rs.getInt("id"));
                    payload.put("name", rs.getString("name"));
                    out.add(ResultItem.of("favorite", "★ " + rs.getString("name"), "收藏项", "favorite", payload));
                }
            }
        } catch (Exception e) {
            // ignore
        }
    }

    /* ---- 文件搜索 ---- */

    private void searchFiles(String kw, List<ResultItem> out) {
        if (kw.isEmpty()) return;
        Path base = Path.of(System.getProperty("user.home"));
        int max = 8;
        try (Stream<Path> stream = Files.walk(base, 3)) {
            stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().contains(kw.toLowerCase()))
                    .limit(max)
                    .forEach(p -> {
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("path", p.toAbsolutePath().toString());
                        out.add(ResultItem.of("file", p.getFileName().toString(),
                                p.toAbsolutePath().toString(), "open_file", payload));
                    });
        } catch (Exception e) {
            // 权限不足等跳过
        }
    }

    /* ---- 日志 ---- */

    private void logSearch(String kw, int count, long costMs) {
        String sql = "INSERT INTO t_exec_log(kind, keyword, cost_ms, status, message) VALUES('search', ?, ?, ?, ?)";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, kw);
            ps.setLong(2, costMs);
            ps.setString(3, "ok");
            ps.setString(4, count + " results");
            ps.executeUpdate();
        } catch (Exception ignored) {
        }
    }
}
