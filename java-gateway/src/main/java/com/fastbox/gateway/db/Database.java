package com.fastbox.gateway.db;

import com.fastbox.gateway.util.FastBoxPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite 数据库管理
 *
 * 架构硬约束：仅 Java 网关直连 local.db，Electron / Python 一律走 HTTP。
 * 数据文件位置：FastBox/data/local.db（可通过环境变量 FASTBOX_DATA_DIR 覆盖）
 */
public class Database {

    private static final Logger log = LoggerFactory.getLogger(Database.class);

    public static String DB_PATH = FastBoxPaths.dataDir().resolve("local.db").toString();

    static {
        // 环境变量优先（保留旧逻辑兼容）
        String dataDir = System.getenv("FASTBOX_DATA_DIR");
        if (dataDir != null && !dataDir.isBlank()) {
            DB_PATH = Path.of(dataDir, "local.db").toString();
        }
    }

    private Database() {
    }

    /** 初始化：创建数据目录 + 建表 */
    public static void init() throws Exception {
        Path dbFile = Path.of(DB_PATH);
        Path dir = dbFile.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            for (String ddl : SCHEMA) {
                st.execute(ddl);
            }
            // 已有库迁移：低版本 t_plugin 无 args_schema 列
            try {
                st.execute("ALTER TABLE t_plugin ADD COLUMN args_schema TEXT NOT NULL DEFAULT '[]'");
            } catch (SQLException ignored) {
                // 列已存在，忽略
            }
            // 已有库迁移：t_exec_log 增加 plugin_name 列（插件级留痕）
            try {
                st.execute("ALTER TABLE t_exec_log ADD COLUMN plugin_name TEXT NOT NULL DEFAULT ''");
            } catch (SQLException ignored) {
                // 列已存在，忽略
            }
        }
        log.info("数据表就绪: {}", dbFile.toAbsolutePath());
    }

    public static Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
        // WAL 模式提升并发读性能
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("PRAGMA foreign_keys=ON");
        }
        return conn;
    }

    /** 建表 DDL（幂等） */
    private static final String[] SCHEMA = {
            """
            CREATE TABLE IF NOT EXISTS t_config (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL DEFAULT ''
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS t_shortcut (
                id       INTEGER PRIMARY KEY AUTOINCREMENT,
                name     TEXT NOT NULL,
                shortcut TEXT NOT NULL,
                enabled  INTEGER NOT NULL DEFAULT 1
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS t_plugin (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                name       TEXT NOT NULL,
                kind       TEXT NOT NULL CHECK (kind IN ('js','java','python')),
                path       TEXT NOT NULL,
                keywords   TEXT NOT NULL DEFAULT '',
                args_schema TEXT NOT NULL DEFAULT '[]',
                enabled    INTEGER NOT NULL DEFAULT 1,
                installed_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS t_search_history (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                keyword   TEXT NOT NULL,
                clicked_action TEXT DEFAULT '',
                created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS t_favorite (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                name       TEXT NOT NULL,
                action     TEXT NOT NULL DEFAULT '',
                payload    TEXT NOT NULL DEFAULT '{}',
                created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS t_sql_script (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                name        TEXT NOT NULL UNIQUE,
                description TEXT NOT NULL DEFAULT '',
                sql_text    TEXT NOT NULL,
                created_at  TEXT NOT NULL DEFAULT (datetime('now','localtime')),
                updated_at  TEXT NOT NULL DEFAULT (datetime('now','localtime'))
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS t_exec_log (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                kind      TEXT NOT NULL,
                keyword   TEXT NOT NULL DEFAULT '',
                action    TEXT NOT NULL DEFAULT '',
                plugin_name TEXT NOT NULL DEFAULT '',
                cost_ms   INTEGER NOT NULL DEFAULT 0,
                status    TEXT NOT NULL DEFAULT 'ok',
                message   TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
            )
            """,
    };
}
