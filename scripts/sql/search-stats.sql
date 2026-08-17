-- name: 搜索历史统计
-- description: 搜索关键词使用频次排行（最近100条历史）
SELECT keyword AS 关键词,
       COUNT(*) AS 次数,
       MAX(created_at) AS 最近使用
FROM t_search_history
GROUP BY keyword
ORDER BY 次数 DESC
LIMIT 20
