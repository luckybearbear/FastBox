package com.fastbox.gateway.server;

import com.fastbox.gateway.python.PythonClient;
import com.fastbox.gateway.service.ActionService;
import com.fastbox.gateway.service.SearchService;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP 网关路由
 * 统一 REST 接口：搜索、动作执行、SQL、插件管理
 */
public class ApiRoutes {

    private static final String VERSION = "0.1.0";

    private final SearchService search = new SearchService();
    private final ActionService action = new ActionService();
    private final PythonClient python = new PythonClient();

    public static void register(Javalin app) {
        new ApiRoutes().wire(app);
    }

    private void wire(Javalin app) {
        app.get("/health", this::health);
        app.get("/api/search", this::search);
        app.post("/api/action", this::action);
        app.get("/api/plugins", this::listPlugins);
        app.post("/api/sql/execute", this::sqlExecute);
        app.get("/api/sql/scripts", this::listSqlScripts);
        app.post("/api/sql/save", this::saveSqlScript);
        // 执行留痕：Electron 主进程上报 JS 插件执行记录 + 查询入口
        app.post("/api/exec-log", this::execLog);
        app.get("/api/exec-logs", this::execLogs);
        // 搜索历史
        app.get("/api/history", this::listHistory);
        // 收藏管理
        app.get("/api/favorites", this::listFavorites);
        app.post("/api/favorites", this::addFavorite);
        app.post("/api/favorites/delete", this::deleteFavorite);
    }

    /* ---- GET /health ---- */

    private void health(Context ctx) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("version", VERSION);
        body.put("python", python.isAlive() ? "up" : "down");
        ctx.json(body);
    }

    /* ---- GET /api/search?q=xxx ---- */

    private void search(Context ctx) {
        String q = ctx.queryParam("q");
        List<Map<String, Object>> results = search.search(q == null ? "" : q)
                .stream().map(r -> r.toMap()).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", q == null ? "" : q);
        body.put("count", results.size());
        body.put("results", results);
        ctx.json(body);
    }

    /* ---- POST /api/action {action, payload} ---- */

    @SuppressWarnings("unchecked")
    private void action(Context ctx) {
        Map<String, Object> req = ctx.bodyAsClass(Map.class);
        String act = String.valueOf(req.getOrDefault("action", ""));
        Map<String, Object> payload = (Map<String, Object>) req.getOrDefault("payload", Map.of());
        ctx.json(action.execute(act, payload));
    }

    /* ---- GET /api/plugins ---- */

    private void listPlugins(Context ctx) {
        ctx.json(action.execute("list_plugins", Map.of()));
    }

    /* ---- POST /api/sql/execute {sql} ---- */

    @SuppressWarnings("unchecked")
    private void sqlExecute(Context ctx) {
        Map<String, Object> req = ctx.bodyAsClass(Map.class);
        ctx.json(action.runSql(req));
    }

    /* ---- GET /api/sql/scripts ---- */

    private void listSqlScripts(Context ctx) {
        ctx.json(action.execute("list_sql_scripts", Map.of()));
    }

    /* ---- POST /api/sql/save {name, description, sql} ---- */

    @SuppressWarnings("unchecked")
    private void saveSqlScript(Context ctx) {
        Map<String, Object> req = ctx.bodyAsClass(Map.class);
        ctx.json(action.execute("save_sql_script", req));
    }

    /* ---- POST /api/exec-log {kind, pluginName, keyword, args, costMs, status, message} ---- */

    @SuppressWarnings("unchecked")
    private void execLog(Context ctx) {
        Map<String, Object> req = ctx.bodyAsClass(Map.class);
        ctx.json(action.logExternalPlugin(req));
    }

    /* ---- GET /api/exec-logs?limit=50&kind=js ---- */

    private void execLogs(Context ctx) {
        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(50);
        String kind = ctx.queryParam("kind");
        ctx.json(action.listExecLogs(limit, kind));
    }

    /* ---- GET /api/history?limit=20 ---- */

    private void listHistory(Context ctx) {
        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(20);
        ctx.json(Map.of("history", search.listHistory(limit)));
    }

    /* ---- GET /api/favorites ---- */

    private void listFavorites(Context ctx) {
        ctx.json(action.execute("list_favorites", Map.of()));
    }

    /* ---- POST /api/favorites {name, action, payload} ---- */

    @SuppressWarnings("unchecked")
    private void addFavorite(Context ctx) {
        Map<String, Object> req = ctx.bodyAsClass(Map.class);
        ctx.json(action.execute("add_favorite", req));
    }

    /* ---- POST /api/favorites/delete {id} ---- */

    @SuppressWarnings("unchecked")
    private void deleteFavorite(Context ctx) {
        Map<String, Object> req = ctx.bodyAsClass(Map.class);
        ctx.json(action.execute("delete_favorite", req));
    }
}
