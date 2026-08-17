/**
 * FastBox 主进程日志模块
 *
 * 统一写入 data/logs/electron.log（与网关 / Python 日志同目录），
 * 支持 10MB 大小轮转（保留 electron.log.1 一份历史）。
 * 级别由 FASTBOX_LOG_LEVEL 控制：debug < info < warn < error（默认 info）。
 *
 * 数据目录：
 * - 开发模式：FastBox/data/logs/electron.log
 * - 打包模式：app.getPath('userData')/data/logs/electron.log  （Program Files 无写权限，必须走 userData）
 * - 可由 FASTBOX_DATA_DIR 环境变量强制覆盖（CI / 自定义场景）
 */
const fs = require('fs');
const path = require('path');

function resolveDataDir() {
  if (process.env.FASTBOX_DATA_DIR) return process.env.FASTBOX_DATA_DIR;
  // require electron 后 app.isPackaged / app.getPath 即可访问
  try {
    const { app } = require('electron');
    if (app.isPackaged) return path.join(app.getPath('userData'), 'data');
  } catch {
    // 不在 Electron 进程内（如 CLI 工具直接 require logger）
  }
  // 开发模式：与 main.js resolvePaths 一致
  return path.join(__dirname, '..', '..', '..', 'data');
}

const DATA_DIR = resolveDataDir();
const LOG_DIR = path.join(DATA_DIR, 'logs');
const LOG_FILE = path.join(LOG_DIR, 'electron.log');
const MAX_SIZE = 10 * 1024 * 1024; // 10MB

const LEVELS = { debug: 10, info: 20, warn: 30, error: 40 };
const LEVEL = LEVELS[(process.env.FASTBOX_LOG_LEVEL || 'info').toLowerCase()] ?? LEVELS.info;

let fd = null;

function ensureFd() {
  if (fd) return;
  try {
    fs.mkdirSync(LOG_DIR, { recursive: true });
    // 大小轮转：超过阈值时旧文件重命名为 electron.log.1，更早的丢弃
    if (fs.existsSync(LOG_FILE) && fs.statSync(LOG_FILE).size >= MAX_SIZE) {
      const bak = LOG_FILE + '.1';
      if (fs.existsSync(bak)) fs.unlinkSync(bak);
      fs.renameSync(LOG_FILE, bak);
    }
    fd = fs.openSync(LOG_FILE, 'a');
  } catch (e) {
    fd = null; // 日志不可写时降级为 no-op，不阻塞主进程
  }
}

function stringify(v) {
  if (typeof v === 'string') return v;
  try {
    return JSON.stringify(v);
  } catch {
    return String(v);
  }
}

function write(level, args) {
  const lv = LEVELS[level];
  if (lv < LEVEL) return;
  ensureFd();
  if (!fd) return;
  const line = `[${new Date().toISOString()}] [${level.toUpperCase()}] ${args.map(stringify).join(' ')}\n`;
  try {
    fs.writeSync(fd, line);
  } catch (e) {
    /* 磁盘满等场景静默 */
  }
}

module.exports = {
  debug: (...a) => write('debug', a),
  info: (...a) => write('info', a),
  warn: (...a) => write('warn', a),
  error: (...a) => write('error', a),
  LOG_FILE,
};
