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

/**
 * 路径解析统一中心 — 同时处理开发模式与打包模式。
 *
 * 开发模式（npm start）：__dirname = FastBox/electron/src/main，上跳 3 级到 FastBox 根。
 * 打包模式（electron-builder）：代码在 app.asar 内，外部组件放在 process.resourcesPath 下：
 *   resources/runtime/gateway/fastbox-gateway.jar
 *   resources/runtime/jre17/bin/java(.exe)
 *   resources/runtime/python/.venv/Scripts/python.exe
 *   resources/runtime/python/main.py
 *   resources/plugins/js/<name>/main.js
 *   resources/plugins/{python,java}/...  （跟随 python-runtime/plugins / plugins/java 镜像）
 *
 * 数据目录（SQLite + 日志）：打包后必须可写，因此走 Electron 的 userData（避免 Program Files 无权限）
 * 开发模式仍走 FastBox/data。
 */
function resolvePaths() {
  const isPackaged = app.isPackaged;
  const resourcesPath = process.resourcesPath;

  if (isPackaged) {
    // 打包模式：外部组件路径统一基于 process.resourcesPath
    const javaBin = process.platform === 'win32' ? 'java.exe' : 'java';
    const pythonBin = process.platform === 'win32' ? 'python.exe' : 'python';
    return {
      isPackaged: true,
      gatewayJar: path.join(resourcesPath, 'runtime', 'gateway', 'fastbox-gateway.jar'),
      javaExe: path.join(resourcesPath, 'runtime', 'jre17', 'bin', javaBin),
      pythonExe: path.join(resourcesPath, 'runtime', 'python', '.venv',
        process.platform === 'win32' ? 'Scripts' : 'bin', pythonBin),
      pythonCwd: path.join(resourcesPath, 'runtime', 'python'),
      jsPluginsDir: path.join(resourcesPath, 'plugins', 'js'),
      // 数据目录用 userData（Windows 上默认 %APPDATA%\FastBox），子目录 data/ 与开发模式保持一致
      dataDir: path.join(app.getPath('userData'), 'data'),
    };
  }
  // 开发模式：从 main.js 上跳 3 级到 FastBox 根（electron/src/main → electron/src → electron → FastBox）
  const projectRoot = path.resolve(__dirname, '..', '..', '..');
  return {
    isPackaged: false,
    gatewayJar: process.env.FASTBOX_GATEWAY_JAR
      || path.join(projectRoot, 'java-gateway', 'target', 'fastbox-gateway.jar'),
    javaExe: 'java', // 开发模式用系统 PATH 中的 java
    // Python 在开发模式由用户手动启动；spawn 调用方需先判断 ensurePython / 已 alive
    pythonExe: null,
    pythonCwd: null,
    jsPluginsDir: path.join(projectRoot, 'plugins', 'js'),
    dataDir: process.env.FASTBOX_DATA_DIR || path.join(projectRoot, 'data'),
  };
}

const PATHS = resolvePaths();
console.log(`[paths] packaged=${PATHS.isPackaged} data=${PATHS.dataDir}`);

// 子进程环境变量：把数据目录注入 Java/Python 子进程，避免它们各自依赖 user.dir / __file__ 推导
const CHILD_ENV = { ...process.env, FASTBOX_DATA_DIR: PATHS.dataDir };
const GATEWAY_URL = process.env.FASTBOX_GATEWAY_URL || 'http://127.0.0.1:8764';
const TOGGLE_SHORTCUT = process.env.FASTBOX_SHORTCUT || 'CommandOrControl+Shift+Space';

let mainWindow = null;
let tray = null;
let gatewayProc = null;
let pythonProc = null;
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
    const javaHome = PATHS.isPackaged ? path.dirname(path.dirname(PATHS.javaExe)) : null;
    const env = javaHome ? { ...CHILD_ENV, JAVA_HOME: javaHome } : CHILD_ENV;
    gatewayProc = spawn(PATHS.javaExe, ['-jar', PATHS.gatewayJar], {
      cwd: PATHS.dataDir,
      env,
      stdio: 'ignore',
      detached: true,
      windowsHide: true,
    });
    gatewayProc.unref();
    console.log(`[gateway] 已 spawn: ${PATHS.javaExe} -jar ${PATHS.gatewayJar}`);
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

async function isPythonAlive() {
  try {
    const res = await httpGet('http://127.0.0.1:8765/health', 800);
    return res.status === 200;
  } catch {
    return false;
  }
}

/**
 * 确保 Python FastAPI 在 8765 在线。
 * - 打包模式：从 bundled .venv 启动，cwd 设为 runtime/python（main.py 在此）
 * - 开发模式：若 Python 已手动启动则放行；否则提示用户在终端执行 `python python-runtime/main.py`
 */
async function ensurePython() {
  if (await isPythonAlive()) return true;
  if (!PATHS.pythonExe) {
    console.warn('[python] 开发模式未配置自拉起，请手动启动: python python-runtime/main.py');
    return false;
  }
  if (!fs.existsSync(PATHS.pythonExe)) {
    console.error(`[python] 解释器不存在: ${PATHS.pythonExe}`);
    return false;
  }
  try {
    pythonProc = spawn(PATHS.pythonExe, ['main.py'], {
      cwd: PATHS.pythonCwd,
      env: CHILD_ENV,
      stdio: 'ignore',
      detached: true,
      windowsHide: true,
    });
    pythonProc.unref();
    console.log(`[python] 已 spawn: ${PATHS.pythonExe} main.py (cwd=${PATHS.pythonCwd})`);
    for (let i = 0; i < 20; i++) {
      await new Promise((r) => setTimeout(r, 500));
      if (await isPythonAlive()) return true;
    }
    return false;
  } catch (e) {
    console.error('[python] 拉起失败:', e.message);
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

const JS_PLUGINS_DIR = PATHS.jsPluginsDir;

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
  // Python 是 Java 网关的依赖，确保 Java 已通后再补拉起 Python
  await ensurePython();
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

  // 后台拉起网关 + Python（不阻塞启动）
  setTimeout(async () => {
    const alive = await ensureGateway();
    await ensurePython();
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
  // 关停主进程拉起的子进程
  if (gatewayProc) { try { gatewayProc.kill(); } catch {} }
  if (pythonProc) { try { pythonProc.kill(); } catch {} }
});

app.on('window-all-closed', (e) => {
  // 托盘常驻，不退出
});
