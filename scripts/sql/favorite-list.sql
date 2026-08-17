-- name: 收藏清单
-- description: 当前全部收藏项及其对应动作
SELECT name AS 名称,
       action AS 动作,
       created_at AS 收藏时间
FROM t_favorite
ORDER BY created_at DESC
