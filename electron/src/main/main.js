/**
 * FastBox 主进程
 * 职责：全局热键、系统托盘、悬浮面板窗口管理、网关健康检查与拉起
 */
const { app, BrowserWindow, globalShortcut, Tray, Menu, ipcMain, screen, nativeImage } = require('electron');
const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');
const http = require('http');
const logger = require('./logger');

// 主进程 console 统一落盘（data/logs/electron.log），级别由 FASTBOX_LOG_LEVEL 控制
for (const [m, lv] of [['log', 'info'], ['info', 'info'], ['warn', 'warn'], ['error', 'error'], ['debug', 'debug']]) {
  const orig = console[m].bind(console);
  console[m] = (...args) => {
    orig(...args);
    logger[lv](...args);
  };
}

// 必须在 app ready 之前调用：沙箱/远程桌面/虚拟机环境 GPU 不可用，禁用硬件加速避免崩溃
app.disableHardwareAcceleration();
app.commandLine.appendSwitch('disable-gpu');
app.commandLine.appendSwitch('disable-gpu-compositing');
// GPU 进程在本环境一启动即退出，改为进程内嵌 GPU + 关闭 Chromium 沙箱绕开
app.commandLine.appendSwitch('in-process-gpu');
app.commandLine.appendSwitch('no-sandbox');

const GATEWAY_URL = process.env.FASTBOX_GATEWAY_URL || 'http://127.0.0.1:8764';
const GATEWAY_JAR = process.env.FASTBOX_GATEWAY_JAR || path.join(__dirname, '..', '..', 'java-gateway', 'target', 'fastbox-gateway.jar');
const TOGGLE_SHORTCUT = process.env.FASTBOX_SHORTCUT || 'CommandOrControl+Shift+Space';

let mainWindow = null;
let tray = null;
let gatewayProc = null;
let isQuitting = false;

/* ---------------- 网关探测与拉起 ---------------- */

function httpGet(url, timeoutMs = 1500) {
  return new Promise((resolve, reject) => {
    const req = http.get(url, { timeout: timeoutMs }, (res) => {
      let body = '';
      res.on('data', (c) => (body += c));
      res.on('end', () => resolve({ status: res.statusCode, body }));
    });
    req.on('timeout', () => { req.destroy(); reject(new Error('timeout')); });
    req.on('error', reject);
  });
}

async function isGatewayAlive() {
  try {
    const res = await httpGet(`${GATEWAY_URL}/health`, 800);
    return res.status === 200;
  } catch {
    return false;
  }
}

async function ensureGateway() {
  if (await isGatewayAlive()) return true;
  try {
    gatewayProc = spawn('java', ['-jar', GATEWAY_JAR], {
      cwd: path.join(__dirname, '..', '..', 'java-gateway'),
      stdio: 'ignore',
      detached: true,
      windowsHide: true,
    });
    gatewayProc.unref();
    // 最多等 15 秒让 JVM 起来
    for (let i = 0; i < 30; i++) {
      await new Promise((r) => setTimeout(r, 500));
      if (await isGatewayAlive()) return true;
    }
    return false;
  } catch (e) {
    console.error('[gateway] 拉起失败:', e.message);
    return false;
  }
}

/* ---------------- 悬浮面板窗口 ---------------- */

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 680,
    height: 480,
    frame: false,
    resizable: false,
    alwaysOnTop: true,
    skipTaskbar: true,
    show: false,
    transparent: false,
    backgroundColor: '#1e1f22',
    webPreferences: {
      preload: path.join(__dirname, '..', 'preload', 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });

  // FASTBOX_UI_TEST 透传给渲染层做自动化 UI 冒烟验证（如 args=弹参数表单）
  const uiTest = process.env.FASTBOX_UI_TEST || '';
  mainWindow.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'), uiTest ? { query: { uiTest } } : undefined);

  mainWindow.webContents.on('console-message', (_event, level, message) => {
    const tag = ['log', 'warn', 'error'][level] || 'log';
    console.log(`[renderer:${tag}] ${message}`);
  });

  mainWindow.on('blur', () => {
    if (!isQuitting) hidePanel();
  });
  mainWindow.on('close', (e) => {
    if (!isQuitting) {
      e.preventDefault();
      hidePanel();
    }
  });
}

function showPanel() {
  if (!mainWindow) return;
  const { workArea } = screen.getPrimaryDisplay();
  const [w, h] = mainWindow.getSize();
  mainWindow.setPosition(Math.round(workArea.x + (workArea.width - w) / 2), Math.round(workArea.y + 80));
  mainWindow.show();
  mainWindow.focus();
  mainWindow.webContents.send('panel:shown');
}

function hidePanel() {
  if (mainWindow) mainWindow.hide();
}

function togglePanel() {
  if (mainWindow && mainWindow.isVisible()) hidePanel();
  else showPanel();
}

/* ---------------- 托盘 ---------------- */

/** 生成 16x16 蓝色方块托盘图标（避免空图标 libpng 警告） */
function createTrayIcon() {
  const b64 = 'iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAU0lEQVR42mNgGAWjYBSMglEwCkbBKBgFo2AUjIJRMApGwSgYBaNgFIyCUTAKRsEoGAWjYBSMglEwCkbBKBgFo2AUjIJRMApGwSgYBaNgFIyCUTAKRsEoGAWjYBSMglEwCkbBKBgFo2AUjIJRMApGwSgYBQAeKAAAAAElFTkSuQmCC';
  return nativeImage.createFromDataURL('data:image/png;base64,' + b64);
}

function createTray() {
  tray = new Tray(createTrayIcon());
  const menu = Menu.buildFromTemplate([
    { label: '打开 FastBox', click: showPanel },
    { type: 'separator' },
    {
      label: '重启网关服务',
      click: async () => {
        mainWindow.webContents.send('status:toast', '正在重启网关…');
        if (gatewayProc) { try { gatewayProc.kill(); } catch {} }
        const ok = await ensureGateway();
        mainWindow.webContents.send('status:toast', ok ? '网关已就绪' : '网关启动失败，请查看 data/logs');
      },
    },
    { type: 'separator' },
    { label: '退出', click: () => { isQuitting = true; app.quit(); } },
  ]);
  tray.setToolTip('FastBox — 快捷启动工具');
  tray.setContextMenu(menu);
  tray.on('click', togglePanel);
}

/* ---------------- JS 插件执行器 ---------------- */

const JS_PLUGINS_DIR = path.join(__dirname, '..', '..', '..', 'plugins', 'js');

/**
 * 在主进程内执行 JS 插件
 * 插件目录：plugins/js/<name>/main.js，导出 { run(keyword, args, userConfig) -> object }
 * 返回格式与 Java 网关统一：{ detail?: {title, content}, toast?: string, data?: any }
 * 执行结果统一上报网关 t_exec_log 留痕（失败静默，不影响执行结果）
 */
function executeJsPlugin(payload) {
  const name = payload.name || '';
  const keyword = payload.keyword || '';
  const args = Array.isArray(payload.args) ? payload.args : [];
  const userConfig = {};
  const start = Date.now();

  const pluginDir = path.join(JS_PLUGINS_DIR, name);
  const entryFile = path.join(pluginDir, 'main.js');

  let result;
  if (!fs.existsSync(entryFile)) {
    result = { toast: `JS 插件不存在: ${name}` };
  } else {
    try {
      // 清除 require 缓存实现热重载（开发友好）
      delete require.cache[require.resolve(entryFile)];
      const plugin = require(entryFile);
      if (typeof plugin.run !== 'function') {
        result = { toast: `JS 插件 ${name} 未导出 run 函数` };
      } else {
        const ret = plugin.run(keyword, args, userConfig);
        console.log(`[js-plugin] ${name} 执行完成, keyword="${keyword}", args=${JSON.stringify(args)}`);
        // 统一返回格式
        if (ret && ret.detail) {
          result = {
            detail: {
              title: ret.detail.title || name,
              content: typeof ret.detail.content === 'string'
                ? ret.detail.content
                : JSON.stringify(ret.detail.content, null, 2),
            },
            toast: ret.toast || '执行完成',
          };
        } else if (ret && ret.toast) {
          result = { toast: ret.toast };
        } else {
          result = {
            detail: { title: name, content: typeof ret === 'string' ? ret : JSON.stringify(ret, null, 2) },
            toast: '执行完成',
          };
        }
      }
    } catch (e) {
      console.error(`[js-plugin] ${name} 执行失败:`, e.message);
      result = { toast: `JS 插件执行失败: ${e.message}` };
    }
  }

  // 留痕上报：失败/异常置 fail，其余 ok（上报失败静默，不阻塞主流程）
  const toast = result.toast || '';
  const status = /失败|不存在|未导出|异常/.test(toast) ? 'fail' : 'ok';
  reportExecLog({
    kind: 'js',
    pluginName: name,
    keyword,
    args,
    costMs: Date.now() - start,
    status,
    message: toast,
  });
  return result;
}

/** 异步上报执行留痕到网关，失败静默 */
function reportExecLog(body) {
  httpRequest('POST', `${GATEWAY_URL}/api/exec-log`, body, 3000).catch(() => {});
}

/* ---------------- IPC ---------------- */

ipcMain.handle('gateway:call', async (_evt, method, endpoint, body) => {
  await ensureGateway();
  return await httpRequest(method, `${GATEWAY_URL}${endpoint}`, body);
});

ipcMain.handle('panel:hide', () => hidePanel());

ipcMain.handle('plugin:execute-js', (_evt, payload) => {
  return executeJsPlugin(payload);
});

function httpRequest(method, url, body, timeoutMs = 15000) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const payload = body === undefined ? null : JSON.stringify(body);
    const req = http.request(
      {
        hostname: u.hostname,
        port: u.port,
        path: u.pathname + u.search,
        method,
        timeout: timeoutMs,
        headers: payload
          ? { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) }
          : {},
      },
      (res) => {
        let data = '';
        res.on('data', (c) => (data += c));
        res.on('end', () => {
          try { resolve({ status: res.statusCode, body: JSON.parse(data) }); }
          catch { resolve({ status: res.statusCode, body: data }); }
        });
      }
    );
    req.on('timeout', () => { req.destroy(); reject(new Error('网关超时')); });
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

/* ---------------- 生命周期 ---------------- */

app.whenReady().then(async () => {
  createWindow();
  createTray();

  const ok = globalShortcut.register(TOGGLE_SHORTCUT, togglePanel);
  console.log(`[hotkey] ${TOGGLE_SHORTCUT} ${ok ? '注册成功' : '注册失败（可能被占用）'}`);

  // 后台拉起网关（不阻塞启动）
  setTimeout(async () => {
    const alive = await ensureGateway();
    if (mainWindow) {
      mainWindow.webContents.send('status:gateway', alive ? 'ready' : 'failed');
      mainWindow.webContents.send('status:toast', alive ? '网关服务已就绪' : '网关启动失败，请查看 data/logs');
    }
  }, 800);

  // 调试用：FASTBOX_SHOW_ON_START=1 时启动即显示面板
  if (process.env.FASTBOX_SHOW_ON_START === '1') {
    setTimeout(showPanel, 1500);
  }

  // 调试用：FASTBOX_CAPTURE_PNG=<路径> 时延迟截屏保存后退出（UI 冒烟验证）
  if (process.env.FASTBOX_CAPTURE_PNG) {
    const delay = Number(process.env.FASTBOX_CAPTURE_DELAY || 6000);
    setTimeout(async () => {
      try {
        const img = await mainWindow.webContents.capturePage();
        fs.writeFileSync(process.env.FASTBOX_CAPTURE_PNG, img.toPNG());
        console.log('[capture] saved ->', process.env.FASTBOX_CAPTURE_PNG);
        isQuitting = true;
        app.quit();
      } catch (e) {
        console.error('[capture] failed:', e.message);
        app.exit(1);
      }
    }, delay);
  }

  app.on('activate', () => showPanel());
});

app.on('will-quit', () => {
  globalShortcut.unregisterAll();
});

app.on('window-all-closed', (e) => {
  // 托盘常驻，不退出
});
