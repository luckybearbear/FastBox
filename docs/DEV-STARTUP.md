# FastBox 本地测试启动说明

> 开发模式下的本地启动、调试与冒烟验证指南。
> 三件套架构：Electron（UI）→ Java 网关（8764）→ Python FastAPI（8765）→ SQLite。

---

## 1. 前置环境

| 依赖 | 版本 | 用途 | 检查命令 |
|---|---|---|---|
| Node.js | 18+ | Electron 运行 | `node -v` |
| JDK | 17+ | Java 网关编译运行 | `java -version` |
| Maven | 3.8+ | Java 网关构建 | `mvn -v` |
| Python | 3.11+（推荐 3.13） | FastAPI 常驻服务 | `python --version` |

> **Windows 注意**：Maven 命令在 Git Bash 下可能因 `MAVEN_HOME` 反斜杠路径无法启动，建议用 **PowerShell** 跑 `mvn`。

---

## 2. 启动顺序（三个终端）

三个服务有依赖关系：Python ← Java 网关 ← Electron UI。建议按顺序启动，但 Electron 启动后也会自动探测并拉起 Java 网关（Python 在开发模式需手动启动）。

### 终端 1：Python 常驻服务（端口 8765）

```bash
cd D:\devloper\project\myselfProject\FastBox\python-runtime

# 首次运行：安装依赖
pip install -r requirements.txt

# 启动（默认 127.0.0.1:8765）
python main.py
```

**预期输出：**
```
[FastBox Python Runtime] listening on http://127.0.0.1:8765
[FastBox Python Runtime] plugin dir: ...\python-runtime\plugins
[FastBox Python Runtime] log file: ...\data\logs\python.log (level=INFO)
INFO:     Uvicorn running on http://127.0.0.1:8765
```

**验证：**
```bash
curl http://127.0.0.1:8765/health
# → {"status":"ok","version":"0.1.0","plugins":N}
```

### 终端 2：Java 网关（端口 8764）

```bash
cd D:\devloper\project\myselfProject\FastBox\java-gateway

# 编译打包（产出 target/fastbox-gateway.jar）
mvn clean package -DskipTests

# 启动（默认 127.0.0.1:8764）
java -jar target/fastbox-gateway.jar
```

**预期输出：**
```
... 网关已启动: http://127.0.0.1:8764
```

**验证：**
```bash
curl http://127.0.0.1:8764/api/plugins
# → [{"id":1,"title":"...","action":"...","payload":{...}}, ...]

curl http://127.0.0.1:8764/api/sql/scripts
# → [{"id":1,"name":"执行日志 Top10","description":"...","sql_text":"..."}, ...]
```

> **端口冲突？** 可用 `--port=9000` 参数改端口：`java -jar target/fastbox-gateway.jar --port=9000`
> 对应 Python：`python main.py --port 9001`
> 然后 Electron 端设环境变量：`FASTBOX_GATEWAY_URL=http://127.0.0.1:9000`

> **jar 被锁无法重新编译？** 旧 Java 进程未退出。PowerShell 执行：
> `Get-Process java | Stop-Process -Force`，然后重新 `mvn clean package`。

### 终端 3：Electron UI

```bash
cd D:\devloper\project\myselfProject\FastBox\electron

# 首次运行：安装依赖
npm install

# 启动（开发模式）
npm start
```

启动后：
- 面板默认隐藏，按 **Ctrl+Shift+Space** 唤起/隐藏
- 系统托盘出现 FastBox 图标，右键可退出
- 主进程会自动探测 Java 网关（8764），未启动时自动 `spawn` 拉起
- **开发模式下 Python 不会自动拉起**（`pythonExe: null`），需终端 1 手动启动

---

## 3. 一键启动（可选）

如果不想开三个终端，可以用以下 PowerShell 脚本一键拉起：

```powershell
# save as scripts/dev-start.ps1
$root = Resolve-Path (Join-Path $PSScriptRoot "..")

# Python
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$root\python-runtime'; python main.py"

# Java gateway
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$root\java-gateway'; mvn -q clean package -DskipTests; java -jar target\fastbox-gateway.jar"

# Electron（等 2 秒让后端先起）
Start-Sleep 2
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$root\electron'; npm start"
```

---

## 4. 调试钩子（环境变量）

在启动 Electron 前设置环境变量，控制调试行为：

### 4.1 面板自动显示

```bash
# Git Bash
FASTBOX_SHOW_ON_START=1 npm start

# PowerShell
$env:FASTBOX_SHOW_ON_START=1; npm start
```

默认面板启动后隐藏，设此变量后延迟 1.5s 自动显示面板，省去按热键的步骤。

### 4.2 自动截屏（UI 冒烟验证）

```bash
# Git Bash
FASTBOX_SHOW_ON_START=1 \
FASTBOX_CAPTURE_PNG=D:/devloper/project/myselfProject/FastBox/data/test.png \
FASTBOX_CAPTURE_DELAY=6000 \
npm start

# PowerShell
$env:FASTBOX_SHOW_ON_START=1
$env:FASTBOX_CAPTURE_PNG="D:\devloper\project\myselfProject\FastBox\data\test.png"
$env:FASTBOX_CAPTURE_DELAY=6000
npm start
```

启动后延迟截屏（默认 6000ms），保存 PNG 后自动退出。配合 `FASTBOX_UI_TEST` 可截取特定交互状态。

### 4.3 UI 自动化测试模式

```bash
# Git Bash
FASTBOX_SHOW_ON_START=1 FASTBOX_UI_TEST=js npm start

# PowerShell
$env:FASTBOX_SHOW_ON_START=1; $env:FASTBOX_UI_TEST="js"; npm start
```

渲染层加载后自动执行预设交互（2.5s 延迟后触发搜索 + 点击），配合截屏可做端到端冒烟验证。

| 模式 | 行为 |
|---|---|
| `args` | 搜索"文件统计"并点击，触发参数输入表单 |
| `js` | 搜索"base64"并点击，触发 JS 插件端到端测试 |
| `java` | 搜索"sha256"并点击，触发 Java 插件测试 |
| `jsfail` | 搜索"fail-test"并点击，触发 JS 插件失败场景 |
| `search` | 搜索"base64"但不点击（仅截图搜索结果） |
| `favorites` | 切换到收藏 Tab（截图收藏面板） |
| `sqlpanel` | 切换到 SQL Tab（截图脚本管理面板） |
| `sql` | 搜索"数据库概览"并点击执行（截图表格结果） |

### 4.4 其他环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `FASTBOX_GATEWAY_URL` | `http://127.0.0.1:8764` | Java 网关地址（改端口时用） |
| `FASTBOX_SHORTCUT` | `CommandOrControl+Shift+Space` | 全局唤起热键 |
| `FASTBOX_DATA_DIR` | `<root>/data`（dev） | 数据目录（SQLite + 日志） |
| `FASTBOX_LOG_LEVEL` | `info` | 日志级别：`debug` / `info` / `warn` / `error` |
| `FASTBOX_GATEWAY_JAR` | `<root>/java-gateway/target/fastbox-gateway.jar` | 网关 jar 路径（覆盖自动探测） |

---

## 5. 日志位置

开发模式下所有日志写入 `data/logs/`：

| 文件 | 来源 | 内容 |
|---|---|---|
| `electron.log` | Electron 主进程 | 路径解析、网关拉起、IPC 调用、渲染层 console 转发 |
| `gateway.log` | Java 网关 | logback 滚动日志，HTTP 请求、插件执行、SQL 操作 |
| `python.log` | Python FastAPI | uvicorn + 插件执行异常，10MB 轮转保留 7 份 |

**快速查看日志：**
```bash
# 实时跟踪主进程日志
tail -f D:/devloper/project/myselfProject/FastBox/data/logs/electron.log

# 渲染层 console 会转发到主进程日志，格式：
# [renderer:log] xxx
# [renderer:warn] xxx
# [renderer:error] xxx
```

---

## 6. 冒烟验证清单

启动三个服务后，按以下步骤验证核心功能：

| # | 操作 | 预期 | 依赖 |
|---|---|---|---|
| 1 | Ctrl+Shift+Space 唤起面板 | 搜索框可见、无白屏 | Electron |
| 2 | 输入"base" | 出现"base64 编码"等 JS 插件结果 | Java 网关 + SQLite |
| 3 | 点击"base64 编码" | 弹出详情面板显示编码结果 | JS 插件执行（Electron 本地） |
| 4 | 输入"文件统计" | 出现结果（Python 插件） | Java 网关 + Python 8765 |
| 5 | 点击"文件统计" | 弹出参数表单 → 输入目录 → 返回统计结果 | Java → HTTP → Python 全链路 |
| 6 | 点击 SQL Tab | 列出 5 个示例脚本 | Java 网关 + SqlScriptScanner |
| 7 | 运行"数据库概览" | 表格渲染各表行数 + CSV 复制按钮可用 | Java → SQLite |
| 8 | 空输入唤起面板 | 展示最近搜索历史 | Java + t_search_history |
| 9 | 搜索结果点星标 | 收藏 Tab 出现该项 | Java + t_favorite |
| 10 | 托盘右键 → 退出 | 三个进程全部退出 | Electron will-quit |

---

## 7. 常见问题排查

| 症状 | 原因 | 解决 |
|---|---|---|
| 面板白屏 | Electron GPU 崩溃（沙箱/远程桌面） | 已内置 `disableHardwareAcceleration` + `--disable-gpu`，如仍白屏查 `electron.log` |
| 搜索无结果 | Java 网关未启动 | `curl http://127.0.0.1:8764/api/plugins`；查 `electron.log` 的 `ensureGateway` 日志 |
| Python 插件执行失败 | Python 8765 未启动 | `curl http://127.0.0.1:8765/health`；开发模式需手动 `python main.py` |
| `mvn clean package` 报 Failed to delete jar | 旧 Java 进程占用 | PowerShell: `Get-Process java \| Stop-Process -Force` |
| Electron 进入纯 Node 模式（无 ipcMain） | 沙箱 `ELECTRON_RUN_AS_NODE` 残留 | 启动前 `unset ELECTRON_RUN_AS_NODE`（Git Bash）或 `Remove-Item Env:ELECTRON_RUN_AS_NODE`（PowerShell） |
| 全局热键无响应 | 快捷键被其他程序占用 | 设 `FASTBOX_SHORTCUT` 环境变量换键 |
| 端口 8764/8765 被占用 | 旧进程未退出 | `netstat -ano \| findstr 8764` 找 PID，`Stop-Process -Id <PID> -Force` |
| `[paths] packaged=false data=...` 路径不对 | jar 路径探测失败 | 设 `FASTBOX_GATEWAY_JAR` 环境变量指向正确 jar |

---

## 8. 开发模式 vs 打包模式差异

| 项目 | 开发模式 (`npm start`) | 打包模式 |
|---|---|---|
| Java 网关 | 系统 PATH 的 `java` + `target/fastbox-gateway.jar` | 内置 jlink JRE + `resources/runtime/gateway/...` |
| Python | **手动启动** `python main.py` | Electron 自动拉起便携 venv |
| 数据目录 | `<FastBox>/data/` | `%APPDATA%/FastBox/data/` |
| 日志目录 | `<FastBox>/data/logs/` | `%APPDATA%/FastBox/data/logs/` |
| JS 插件 | `<FastBox>/plugins/js/` | `resources/plugins/js/` |
| 网关自动拉起 | ✅（探测 8764，未 alive 则 spawn） | ✅ |

> 打包模式验证请参考 `docs/PACKAGING-GUIDE.md`。
