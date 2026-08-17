package com.fastbox.gateway.service;

import com.fastbox.gateway.db.Database;
import com.fastbox.gateway.util.FastBoxPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.stream.Stream;

/**
 * SQL 脚本扫描器 — 启动时扫描 scripts/sql/*.sql，幂等注册到 t_sql_script
 *
 * 文件格式约定：
 *   首行注释声明元信息（SQLite 风格 -- 注释）：
 *     -- name: 脚本名称（缺省用文件名去扩展名）
 *     -- description: 用途说明
 *   其余内容为 SQL 正文。
 *
 * 同名脚本：文件内容与库内不同则更新（以文件为准），相同则跳过。
 */
public class SqlScriptScanner {

    private static final Logger log = LoggerFactory.getLogger(SqlScriptScanner.class);

    /** 脚本数量上限（防御） */
    private static final int MAX_SCRIPTS = 100;

    private final Path baseDir;

    public SqlScriptScanner() {
        this.baseDir = FastBoxPaths.sqlScripts();
    }

    /** 扫描并注册，返回新注册数量 */
    public int scan() {
        if (!Files.isDirectory(baseDir)) {
            log.info("[sql] 脚本目录不存在，跳过扫描: {}", baseDir);
            return 0;
        }
        int registered = 0;
        try (Stream<Path> files = Files.list(baseDir)) {
            for (Path file : files.filter(f -> f.toString().toLowerCase().endsWith(".sql")).toList()) {
                try {
                    String content = Files.readString(file);
                    String[] meta = parseMeta(content);
                    String name = meta[0].isBlank() ? stripExt(file.getFileName().toString()) : meta[0];
                    String description = meta[1];
                    String sqlText = stripMeta(content).trim();
                    if (sqlText.isBlank()) {
                        log.warn("[sql] 脚本为空，跳过: {}", file);
                        continue;
                    }
                    if (upsert(name, description, sqlText)) registered++;
                } catch (Exception e) {
                    log.warn("[sql] 解析失败 {}: {}", file, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[sql] 扫描目录失败 {}: {}", baseDir, e.getMessage());
        }
        return registered;
    }

    /** 解析首部元信息，返回 {name, description} */
    private String[] parseMeta(String content) {
        String name = "";
        String description = "";
        for (String line : content.split("\n", 20)) {
            String t = line.trim();
            if (!t.startsWith("--")) break;              // 元信息必须连续放在文件头部
            if (t.startsWith("-- name:")) name = t.substring("-- name:".length()).trim();
            else if (t.startsWith("-- description:")) description = t.substring("-- description:".length()).trim();
        }
        return new String[]{name, description};
    }

    /** 去掉文件头部的连续注释行，返回 SQL 正文 */
    private String stripMeta(String content) {
        StringBuilder sb = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            String t = line.trim();
            if (sb.length() == 0 && (t.startsWith("--") || t.isBlank())) continue;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private String stripExt(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i > 0 ? fileName.substring(0, i) : fileName;
    }

    /** 幂等注册：同名存在且内容相同跳过；不同则更新（以文件为准） */
    private boolean upsert(String name, String description, String sqlText) throws Exception {
        try (Connection conn = Database.getConnection();
             PreparedStatement check = conn.prepareStatement(
                     "SELECT id, sql_text FROM t_sql_script WHERE name = ?")) {
            check.setString(1, name);
            try (ResultSet rs = check.executeQuery()) {
                if (rs.next()) {
                    if (sqlText.equals(rs.getString("sql_text"))) return false;
                    try (PreparedStatement upd = conn.prepareStatement(
                            "UPDATE t_sql_script SET description = ?, sql_text = ?, updated_at = datetime('now','localtime') WHERE id = ?")) {
                        upd.setString(1, description);
                        upd.setString(2, sqlText);
                        upd.setInt(3, rs.getInt("id"));
                        upd.executeUpdate();
                    }
                    log.info("[sql] 已更新脚本: {}", name);
                    return false;
                }
                // 新增前检查上限
                try (PreparedStatement cnt = conn.prepareStatement("SELECT COUNT(*) FROM t_sql_script");
                     ResultSet crs = cnt.executeQuery()) {
                    if (crs.next() && crs.getInt(1) >= MAX_SCRIPTS) {
                        log.warn("[sql] 脚本数量已达上限 {}，跳过: {}", MAX_SCRIPTS, name);
                        return false;
                    }
                }
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO t_sql_script(name, description, sql_text) VALUES(?, ?, ?)")) {
                    ins.setString(1, name);
                    ins.setString(2, description);
                    ins.setString(3, sqlText);
                    ins.executeUpdate();
                }
                log.info("[sql] 已注册脚本: {}", name);
                return true;
            }
        }
    }
}
