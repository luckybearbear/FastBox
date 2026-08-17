package com.fastbox.gateway.service;

import com.fastbox.gateway.db.Database;
import com.fastbox.gateway.plugin.JavaPluginLoader;
import com.fastbox.gateway.python.PythonClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动作执行器
 * 统一处理前端点击结果后的动作：计算、打开文件、执行插件、执行 SQL 等
 */
public class ActionService {

    private static final Logger log = LoggerFactory.getLogger(ActionService.class);
    private final PythonClient python = new PythonClient();
    private final JavaPluginLoader javaLoader = new JavaPluginLoader();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 执行动作，返回统一 JSON */
    public Map<String, Object> execute(String action, Map<String, Object> payload) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> result = switch (action) {
                case "calc" -> runCalc(payload);
                case "open_file" -> openFile(payload);
                case "plugin" -> runPlugin(payload);
                case "sql_script" -> runSqlScript(payload);
                case "sql" -> runSql(payload);
                case "favorite" -> runFavorite(payload);
                case "help" -> showHelp();
                case "list_plugins" -> listPlugins();
                case "list_sql_scripts" -> listSqlScripts();
                case "save_sql_script" -> saveSqlScript(payload);
                case "list_favorites" -> listFavorites();
                case "add_favorite" -> addFavorite(payload);
                case "delete_favorite" -> deleteFavorite(payload);
                default -> Map.of("toast", "未知动作: " + action);
            };
            // 插件动作在 runPlugin 内部记插件级留痕（含插件名/参数摘要），此处跳过避免重复
            if (!"plugin".equals(action)) {
                logAction(action, payload, System.currentTimeMillis() - start, "ok", "");
            }
            return result;
        } catch (Exception e) {
            log.warn("动作执行失败 {}: {}", action, e.getMessage());
            if (!"plugin".equals(action)) {
                logAction(action, payload, System.currentTimeMillis() - start, "fail", e.getMessage());
            }
            return Map.of("toast", "执行失败: " + e.getMessage());
        }
    }

    /* ---- 计算器 ---- */

    private Map<String, Object> runCalc(Map<String, Object> payload) {
        String expr = String.valueOf(payload.getOrDefault("expr", "")).trim();
        if (expr.isBlank()) {
            return Map.of("toast", "请输入表达式");
        }
        // 安全校验：只允许数学表达式字符
        if (!expr.matches("^[0-9+\\-*/().%\\s]+$")) {
            return Map.of("toast", "表达式包含非法字符");
        }
        Expression e = new ExpressionBuilder(expr).build();
        double val = e.evaluate();
        String result = (val == Math.floor(val) && !Double.isInfinite(val))
                ? String.valueOf((long) val) : String.valueOf(val);
        return Map.of(
                "detail", Map.of(
                        "title", "计算结果",
                        "content", expr + " = " + result),
                "toast", "计算完成"
        );
    }

    /* ---- 打开文件 ---- */

    private Map<String, Object> openFile(Map<String, Object> payload) {
        String path = String.valueOf(payload.getOrDefault("path", ""));
        File f = new File(path);
        if (!f.exists()) {
            return Map.of("toast", "文件不存在: " + path);
        }
        if (Desktop.isDesktopSupported()) {
            try {
                Desktop.getDesktop().open(f);
                return Map.of("toast", "已打开: " + f.getName());
            } catch (java.io.IOException e) {
                return Map.of("toast", "打开失败: " + e.getMessage());
            }
        }
        return Map.of("toast", "当前系统不支持桌面打开");
    }

    /* ---- 执行插件 ---- */

    private Map<String, Object> runPlugin(Map<String, Object> payload) {
        int pluginId = ((Number) payload.getOrDefault("pluginId", 0)).intValue();
        String keyword = String.valueOf(payload.getOrDefault("keyword", ""));
        String kind = String.valueOf(payload.getOrDefault("kind", ""));
        @SuppressWarnings("unchecked")
        List<String> args = (List<String>) payload.getOrDefault("args", List.of());

        String sql = "SELECT name, path, args_schema FROM t_plugin WHERE id = ? AND enabled = 1";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, pluginId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Map.of("toast", "插件不存在或已禁用");
                }
                String name = rs.getString("name");
                String path = rs.getString("path");
                String argsSchema = rs.getString("args_schema");

                // 必填参数校验（后端兜底，防止绕过 UI 直接调用）
                String missing = missingRequired(argsSchema, args);
                if (missing != null) {
                    return Map.of("toast", "缺少必填参数: " + missing);
                }

                if ("python".equals(kind)) {
                    long s = System.currentTimeMillis();
                    Map<String, Object> r = runPythonPlugin(name, path, keyword, args);
                    logPluginAction("python", name, keyword, args, System.currentTimeMillis() - s, r);
                    return r;
                }
                if ("java".equals(kind)) {
                    long s = System.currentTimeMillis();
                    Map<String, Object> r = runJavaPlugin(path, keyword, args);
                    logPluginAction("java", name, keyword, args, System.currentTimeMillis() - s, r);
                    return r;
                }
                // JS 插件由 Electron 主进程本地执行（渲染层路由到 IPC）
                return Map.of("toast", kind + " 插件请从面板执行（JS 插件由客户端本地运行）");
            }
        } catch (Exception e) {
            return Map.of("toast", "插件执行异常: " + e.getMessage());
        }
    }

    /** 校验 args_schema 中必填参数是否都有值，返回第一个缺失的参数名；无缺失返回 null */
    private String missingRequired(String argsSchemaJson, List<String> args) {
        if (argsSchemaJson == null || argsSchemaJson.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode schema = mapper.readTree(argsSchemaJson);
            if (!schema.isArray()) return null;
            int idx = 0;
            for (com.fasterxml.jackson.databind.JsonNode field : schema) {
                boolean required = field.path("required").asBoolean(false);
                String name = field.path("name").asText("参数" + (idx + 1));
                String value = idx < args.size() ? String.valueOf(args.get(idx)) : "";
                if (required && (value == null || value.isBlank())) {
                    return name;
                }
                idx++;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Map<String, Object> runPythonPlugin(String name, String scriptPath, String keyword, List<String> args) {
        // Python 端契约：args[0] = 插件目录名，args[1:] = 用户参数
        String dirName = java.nio.file.Path.of(scriptPath).getFileName().toString();
        List<String> call = new ArrayList<>();
        call.add(dirName);
        call.addAll(args);
        JsonNode resp = python.execute(keyword, call, Map.of());
        int code = resp.has("code") ? resp.get("code").asInt(-1) : -1;
        if (code == 0) {
            JsonNode data = resp.get("data");
            if (data != null && data.isObject() && data.has("detail")) {
                return Map.of(
                        "detail", Map.of("title", name, "content", data.get("detail").asText()),
                        "toast", "脚本执行完成");
            }
            return Map.of("toast", "脚本执行完成", "data", data == null ? "" : data.toString());
        }
        String msg = resp.has("message") ? resp.get("message").asText() : "未知错误";
        return Map.of("toast", "脚本执行失败: " + msg);
    }

    /* ---- Java 插件（独立 ClassLoader 反射调用） ---- */

    private Map<String, Object> runJavaPlugin(String pluginPath, String keyword, List<String> args) {
        Map<String, Object> resp = javaLoader.execute(java.nio.file.Path.of(pluginPath), keyword, args);
        int code = ((Number) resp.getOrDefault("code", -1)).intValue();
        if (code == 0) {
            Object data = resp.get("data");
            if (data instanceof Map<?, ?> dm && dm.containsKey("detail")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> detail = (Map<String, Object>) dm.get("detail");
                return Map.of(
                        "detail", Map.of(
                                "title", String.valueOf(detail.getOrDefault("title", "Java 插件")),
                                "content", String.valueOf(detail.getOrDefault("content", ""))),
                        "toast", "插件执行完成");
            }
            return Map.of("toast", "插件执行完成", "data", data == null ? "" : data.toString());
        }
        return Map.of("toast", "插件执行失败: " + resp.getOrDefault("message", "未知错误"));
    }

    /* ---- 执行已保存 SQL 脚本 ---- */

    private Map<String, Object> runSqlScript(Map<String, Object> payload) {
        int scriptId = ((Number) payload.getOrDefault("scriptId", 0)).intValue();
        String sql = "SELECT name, sql_text FROM t_sql_script WHERE id = ?";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, scriptId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Map.of("toast", "SQL 脚本不存在");
                }
                Map<String, Object> inner = new LinkedHashMap<>();
                inner.put("sql", rs.getString("sql_text"));
                return runSql(inner);
            }
        } catch (Exception e) {
            return Map.of("toast", "SQL 执行异常: " + e.getMessage());
        }
    }

    /* ---- 执行任意 SQL（DDL/DML 分离处理） ---- */

    public Map<String, Object> runSql(Map<String, Object> payload) {
        String sqlText = String.valueOf(payload.getOrDefault("sql", "")).trim();
        if (sqlText.isBlank()) {
            return Map.of("toast", "SQL 不能为空");
        }
        // 安全护栏：禁止删除核心配置表
        String lower = sqlText.toLowerCase();
        for (String banned : new String[]{"drop table t_config", "delete from t_config", "drop table t_plugin"}) {
            if (lower.contains(banned)) {
                return Map.of("toast", "禁止操作核心配置表");
            }
        }
        boolean isQuery = lower.trim().startsWith("select") || lower.trim().startsWith("with");
        long start = System.currentTimeMillis();
        try (Connection conn = Database.getConnection(); Statement st = conn.createStatement()) {
            if (isQuery) {
                try (ResultSet rs = st.executeQuery(sqlText)) {
                    List<String> columns = new ArrayList<>();
                    List<List<Object>> rows = new ArrayList<>();
                    int colCount = rs.getMetaData().getColumnCount();
                    for (int i = 1; i <= colCount; i++) {
                        columns.add(rs.getMetaData().getColumnLabel(i));
                    }
                    int limit = 500;
                    while (rs.next() && rows.size() < limit) {
                        List<Object> row = new ArrayList<>();
                        for (int i = 1; i <= colCount; i++) {
                            row.add(rs.getObject(i));
                        }
                        rows.add(row);
                    }
                    return Map.of(
                            "detail", Map.of(
                                    "title", "查询结果 (" + columns.size() + " 列 × " + rows.size() + " 行)",
                                    "content", formatTable(columns, rows)),
                            "columns", columns,
                            "rows", rows,
                            "cost_ms", System.currentTimeMillis() - start);
                }
            } else {
                int affected = st.executeUpdate(sqlText);
                return Map.of("toast", "执行成功，影响 " + affected + " 行",
                        "cost_ms", System.currentTimeMillis() - start);
            }
        } catch (Exception e) {
            return Map.of("toast", "SQL 执行失败: " + e.getMessage());
        }
    }

    private String formatTable(List<String> columns, List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(" | ", columns)).append("\n");
        sb.append("-".repeat(Math.max(20, columns.size() * 10))).append("\n");
        for (List<Object> row : rows) {
            sb.append(String.join(" | ", row.stream().map(String::valueOf).toList())).append("\n");
        }
        return sb.toString();
    }

    /* ---- 收藏动作 ---- */

    private Map<String, Object> runFavorite(Map<String, Object> payload) {
        int favId = ((Number) payload.getOrDefault("favoriteId", 0)).intValue();
        String sql = "SELECT name, action, payload FROM t_favorite WHERE id = ?";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, favId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Map.of("toast", "收藏项不存在");
                }
                String action = rs.getString("action");
                String payloadJson = rs.getString("payload");
                @SuppressWarnings("unchecked")
                Map<String, Object> savedPayload = mapper.readValue(payloadJson, Map.class);
                // 重新执行保存的动作
                return execute(action, savedPayload);
            }
        } catch (Exception e) {
            return Map.of("toast", "收藏执行失败: " + e.getMessage());
        }
    }

    /* ---- 收藏 CRUD ---- */

    @SuppressWarnings("unchecked")
    public Map<String, Object> addFavorite(Map<String, Object> payload) {
        String name = String.valueOf(payload.getOrDefault("name", "")).trim();
        String action = String.valueOf(payload.getOrDefault("action", ""));
        Object payloadObj = payload.getOrDefault("payload", Map.of());
        if (name.isBlank()) {
            return Map.of("toast", "收藏名称不能为空");
        }
        String payloadJson;
        try {
            payloadJson = mapper.writeValueAsString(payloadObj);
        } catch (Exception e) {
            return Map.of("toast", "参数序列化失败: " + e.getMessage());
        }
        String sql = "INSERT INTO t_favorite(name, action, payload) VALUES(?, ?, ?)";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, action);
            ps.setString(3, payloadJson);
            ps.executeUpdate();
            return Map.of("toast", "已收藏: " + name);
        } catch (Exception e) {
            return Map.of("toast", "收藏失败: " + e.getMessage());
        }
    }

    public Map<String, Object> listFavorites() {
        List<Map<String, Object>> favorites = new ArrayList<>();
        String sql = "SELECT id, name, action, payload, created_at FROM t_favorite ORDER BY created_at DESC";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("id", rs.getInt("id"));
                f.put("name", rs.getString("name"));
                f.put("action", rs.getString("action"));
                f.put("payload", mapper.readValue(rs.getString("payload"), Map.class));
                f.put("createdAt", rs.getString("created_at"));
                favorites.add(f);
            }
        } catch (Exception e) {
            return Map.of("toast", "收藏列表加载失败: " + e.getMessage());
        }
        return Map.of("favorites", favorites);
    }

    public Map<String, Object> deleteFavorite(Map<String, Object> payload) {
        int id = ((Number) payload.getOrDefault("id", 0)).intValue();
        if (id <= 0) {
            return Map.of("toast", "无效的收藏 ID");
        }
        String sql = "DELETE FROM t_favorite WHERE id = ?";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            int affected = ps.executeUpdate();
            return affected > 0
                    ? Map.of("toast", "已删除收藏")
                    : Map.of("toast", "收藏项不存在");
        } catch (Exception e) {
            return Map.of("toast", "删除失败: " + e.getMessage());
        }
    }

    /* ---- 插件列表 ---- */

    private Map<String, Object> listPlugins() {
        List<Map<String, Object>> plugins = new ArrayList<>();
        String sql = "SELECT id, name, kind, path, keywords, enabled FROM t_plugin ORDER BY kind, name";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("id", rs.getInt("id"));
                p.put("name", rs.getString("name"));
                p.put("kind", rs.getString("kind"));
                p.put("path", rs.getString("path"));
                p.put("keywords", rs.getString("keywords"));
                p.put("enabled", rs.getInt("enabled") == 1);
                plugins.add(p);
            }
        } catch (Exception e) {
            return Map.of("toast", "插件列表加载失败: " + e.getMessage());
        }
        return Map.of("plugins", plugins);
    }

    /* ---- SQL 脚本列表 ---- */

    private Map<String, Object> listSqlScripts() {
        List<Map<String, Object>> scripts = new ArrayList<>();
        String sql = "SELECT id, name, description, sql_text FROM t_sql_script ORDER BY name";
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", rs.getInt("id"));
                s.put("name", rs.getString("name"));
                s.put("description", rs.getString("description"));
                s.put("sql_text", rs.getString("sql_text"));
                scripts.add(s);
            }
        } catch (Exception e) {
            return Map.of("toast", "SQL 脚本列表加载失败: " + e.getMessage());
        }
        return Map.of("scripts", scripts);
    }

    /* ---- 保存 SQL 脚本 ---- */

    private Map<String, Object> saveSqlScript(Map<String, Object> payload) {
        String name = String.valueOf(payload.getOrDefault("name", "")).trim();
        String sqlText = String.valueOf(payload.getOrDefault("sql", "")).trim();
        String description = String.valueOf(payload.getOrDefault("description", "")).trim();
        if (name.isBlank() || sqlText.isBlank()) {
            return Map.of("toast", "名称和 SQL 内容不能为空");
        }
        String sql = """
                INSERT INTO t_sql_script(name, description, sql_text, updated_at)
                VALUES(?, ?, ?, datetime('now','localtime'))
                ON CONFLICT(name) DO UPDATE SET
                    description = excluded.description,
                    sql_text = excluded.sql_text,
                    updated_at = datetime('now','localtime')
                """;
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, description);
            ps.setString(3, sqlText);
            ps.executeUpdate();
            return Map.of("toast", "SQL 脚本已保存: " + name);
        } catch (Exception e) {
            return Map.of("toast", "SQL 脚本保存失败: " + e.getMessage());
        }
    }

    /* ---- 帮助 ---- */

    private Map<String, Object> showHelp() {
        return Map.of("detail", Map.of(
                "title", "FastBox 使用帮助",
                "content", """
                        快捷键
                        ─────────────────────
                        Ctrl+Shift+Space   唤起/隐藏搜索面板
                        Esc                关闭面板

                        内置工具
                        ─────────────────────
                        calc    计算器（直接输入数学表达式）
                        files   本地文件搜索（file:关键词）
                        help    本帮助

                        SQL 脚本
                        ─────────────────────
                        在设置页保存 SQL 脚本后，输入脚本名即可一键执行

                        插件
                        ─────────────────────
                        Python 插件：python-runtime/plugins/ 下添加脚本 + plugin.json
                        """));
    }

    /* ---- 日志 ---- */

    private void logAction(String action, Map<String, Object> payload, long costMs, String status, String msg) {
        String keyword = payload == null ? "" : String.valueOf(payload.getOrDefault("keyword", ""));
        String sql = "INSERT INTO t_exec_log(kind, action, keyword, cost_ms, status, message) VALUES('action', ?, ?, ?, ?, ?)";
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, action);
            ps.setString(2, keyword);
            ps.setLong(3, costMs);
            ps.setString(4, status);
            ps.setString(5, msg.length() > 500 ? msg.substring(0, 500) : msg);
            ps.executeUpdate();
        } catch (Exception ignored) {
        }
    }

    /**
     * 插件执行留痕（网关内 python/java 插件）
     * result 的 toast 含「失败/异常」视为失败，message 记录 toast 或异常信息
     */
    private void logPluginAction(String kind, String pluginName, String keyword,
                                 List<String> args, long costMs, Map<String, Object> result) {
        String toast = String.valueOf(result.getOrDefault("toast", ""));
        boolean ok = !toast.contains("失败") && !toast.contains("异常");
        insertExecLog(kind, pluginName, keyword, args, costMs, ok ? "ok" : "fail", toast);
    }

    /**
     * 外部执行留痕上报（Electron 主进程执行 JS 插件后调用）
     * body: {kind, pluginName, keyword, args: [], costMs, status, message}
     */
    public Map<String, Object> logExternalPlugin(Map<String, Object> payload) {
        String kind = String.valueOf(payload.getOrDefault("kind", "js"));
        String pluginName = String.valueOf(payload.getOrDefault("pluginName", ""));
        String keyword = String.valueOf(payload.getOrDefault("keyword", ""));
        long costMs = ((Number) payload.getOrDefault("costMs", 0)).longValue();
        String status = String.valueOf(payload.getOrDefault("status", "ok"));
        String message = String.valueOf(payload.getOrDefault("message", ""));
        @SuppressWarnings("unchecked")
        List<String> args = payload.get("args") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList() : List.of();
        insertExecLog(kind, pluginName, keyword, args, costMs, status, message);
        return Map.of("toast", "已记录");
    }

    /** 执行记录查询：GET /api/exec-logs?limit=50&kind=js（kind 可空） */
    public Map<String, Object> listExecLogs(int limit, String kind) {
        List<Map<String, Object>> logs = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT id, kind, keyword, action, plugin_name, cost_ms, status, message, created_at " +
                "FROM t_exec_log WHERE 1=1");
        if (kind != null && !kind.isBlank()) {
            sql.append(" AND kind = ?");
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            if (kind != null && !kind.isBlank()) {
                ps.setString(idx++, kind);
            }
            ps.setInt(idx, Math.min(Math.max(limit, 1), 200));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id"));
                    m.put("kind", rs.getString("kind"));
                    m.put("keyword", rs.getString("keyword"));
                    m.put("action", rs.getString("action"));
                    m.put("pluginName", rs.getString("plugin_name"));
                    m.put("costMs", rs.getInt("cost_ms"));
                    m.put("status", rs.getString("status"));
                    m.put("message", rs.getString("message"));
                    m.put("createdAt", rs.getString("created_at"));
                    logs.add(m);
                }
            }
        } catch (Exception e) {
            return Map.of("toast", "执行记录查询失败: " + e.getMessage());
        }
        return Map.of("logs", logs);
    }

    private void insertExecLog(String kind, String pluginName, String keyword,
                               List<String> args, long costMs, String status, String message) {
        String sql = """
                INSERT INTO t_exec_log(kind, keyword, action, plugin_name, cost_ms, status, message)
                VALUES(?, ?, ?, ?, ?, ?, ?)
                """;
        // 参数摘要：只记前 100 字符，防止敏感信息/超长参数撑爆表
        String argSummary = args == null ? "" : String.join(" ", args);
        if (argSummary.length() > 100) argSummary = argSummary.substring(0, 100) + "...";
        if (message.length() > 500) message = message.substring(0, 500);
        try (Connection conn = Database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, kind);
            ps.setString(2, keyword == null ? "" : keyword);
            ps.setString(3, "plugin:" + pluginName);
            ps.setString(4, pluginName == null ? "" : pluginName);
            ps.setLong(5, costMs);
            ps.setString(6, status);
            ps.setString(7, message);
            ps.executeUpdate();
        } catch (Exception ignored) {
        }
    }
}
