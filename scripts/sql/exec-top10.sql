-- name: 执行日志Top10
-- description: 最近执行的动作/插件耗时 Top10，用于发现慢插件
SELECT keyword AS 关键词,
       plugin_name AS 插件,
       cost_ms AS 耗时ms,
       status AS 状态,
       created_at AS 时间
FROM t_exec_log
ORDER BY cost_ms DESC
LIMIT 10
