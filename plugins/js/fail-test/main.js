/**
 * 异常测试 JS 插件（回归资产）
 * 故意抛出异常，用于验证：
 *   1) 主进程 executeJsPlugin 的 try/catch 是否兜住（返回 {toast} 而非崩溃）
 *   2) 失败留痕是否上报 t_exec_log（status=fail）
 */
module.exports = {
  run(keyword, args, userConfig) {
    throw new Error('fail-test 插件故意抛出的异常');
  },
};
