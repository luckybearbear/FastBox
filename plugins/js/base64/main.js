/**
 * FastBox JS 插件：Base64 编码/解码
 *
 * 模式判定：
 *   - 关键词含「解码」/「decode」→ 解码
 *   - 其他情况 → 编码
 *
 * @param {string} keyword - 触发关键词
 * @param {string[]} args - 用户输入参数，args[0] = 文本
 * @param {object} userConfig - 预留用户配置
 * @returns {{detail?: {title: string, content: string}, toast?: string}}
 */
module.exports = {
  run(keyword, args, userConfig) {
    const text = (args[0] || '').trim();
    if (!text) {
      return { toast: '请提供文本' };
    }

    const isDecode = /解码|decode/i.test(keyword);

    try {
      if (isDecode) {
        const decoded = Buffer.from(text, 'base64').toString('utf-8');
        if (!decoded) {
          return { toast: '解码结果为空，请检查输入' };
        }
        return {
          detail: { title: 'Base64 解码', content: decoded },
          toast: '解码完成',
        };
      } else {
        const encoded = Buffer.from(text, 'utf-8').toString('base64');
        return {
          detail: { title: 'Base64 编码', content: encoded },
          toast: '编码完成',
        };
      }
    } catch (e) {
      return { toast: '操作失败: ' + e.message };
    }
  },
};
