-- name: 数据库概览
-- description: 各数据表行数一览（配置/插件/历史/收藏/日志）
SELECT 't_config' AS 表, COUNT(*) AS 行数 FROM t_config
UNION ALL
SELECT 't_plugin', COUNT(*) FROM t_plugin
UNION ALL
SELECT 't_search_history', COUNT(*) FROM t_search_history
UNION ALL
SELECT 't_favorite', COUNT(*) FROM t_favorite
UNION ALL
SELECT 't_sql_script', COUNT(*) FROM t_sql_script
UNION ALL
SELECT 't_exec_log', COUNT(*) FROM t_exec_log
