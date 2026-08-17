-- name: 插件清单
-- description: 当前已注册的全部插件（类型/关键词/启用状态）
SELECT kind AS 类型,
       name AS 名称,
       keywords AS 关键词,
       CASE enabled WHEN 1 THEN '启用' ELSE '禁用' END AS 状态
FROM t_plugin
ORDER BY kind, name
