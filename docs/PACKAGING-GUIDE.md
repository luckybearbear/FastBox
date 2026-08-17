# FastBox 打包验证指南

> 目标：从源码产出 Windows NSIS 安装包，并在干净环境（或卸载后重装）验证应用可独立运行。
> 关联代码：`scripts/build-runtime.ps1`、`electron/electron-builder.yml`、`electron/src/main/main.js`。

---

## 0. 前置条件

| 依赖 | 版本 | 用途 | 检查命令 |
|---|---|---|---|
| JDK | 17+ | jlink 精简 JRE + 网关编译 | `java -version` |
| Maven | 3.8+ | 编译 Java 网关 shaded jar | `mvn -v` |
| Node.js | 18+ | electron-builder 运行环境 | `node -v` |
| Python | 3.11+（推荐 3.13） | 重建便携 venv | `python --version` |
| PowerShell | 5.1+ | 跑 build-runtime.ps1 | `$PSVersionTable.PSVersion` |

环境变量：`JAVA_HOME` 指向 JDK 根目录（非 `bin`），build-runtime.ps1 会读取它定位 `jlink.exe`。

```
# 验证 JAVA_HOME（PowerShell）
echo $env:JAVA_HOME
# 应输出类似 C:\Program Files\Java\jdk-17
# 如为空：setx JAVA_HOME "C:\Program Files\Java\jdk-17"（需重开终端生效）
```

---

## 1. 生成运行时组件（jlink JRE + 便携 Python）

这一步产出 `runtime/jre17/` 和 `python-runtime/.venv/`，二者是 electron-builder `extraResources` 的来源。

```powershell
# 在 FastBox 项目根目录执行
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-runtime.ps1
```

可选参数：
- `-JreOnly`：只生成 jlink JRE
- `-PyOnly`：只重建便携 venv
- `-JavaHome "C:\path\to\jdk"`：显式指定 JDK（覆盖 JAVA_HOME）

**预期输出关键行：**
```
==> mvn package (fastbox-gateway.jar)        # 网关 jar 编译
==> jdeps 分析依赖模块
    模块清单：java.base,java.logging,java.management,java.naming,java.sql,jdk.crypto.ec,jdk.unsupported
==> jlink 生成精简 JRE → ...\runtime\jre17
    精简 JRE 大小：约 40-60 MB
==> portable venv 重建（python=...）
==> pip install -r requirements.txt
    portable venv 大小：约 30-80 MB
===== 完成 =====
```

**检查点：**
```powershell
# 1. JRE 入口存在
Test-Path runtime\jre17\bin\java.exe        # → True
runtime\jre17\bin\java.exe -version         # → openjdk version "17..."

# 2. 便携 venv 入口存在
Test-Path python-runtime\.venv\Scripts\python.exe  # → True
python-runtime\.venv\Scripts\python.exe -c "import fastapi; print(fastapi.__version__)"

# 3. pyvenv.cfg 跨机可移植性（不应含本机 venv 内部绝对路径）
#    build-runtime.ps1 会自动告警；如告警需换 embeddable Python
Get-Content python-runtime\.venv\pyvenv.cfg
```

**常见问题：**

| 症状 | 原因 | 解决 |
|---|---|---|
| `找不到 jlink.exe` | JAVA_HOME 错误或指向 JRE 非 JDK | 确认 `JAVA_HOME\bin\jlink.exe` 存在；JDK 17 而非 JRE |
| `mvn clean package 失败：Failed to delete fastbox-gateway.jar` | 旧 Java 网关进程占用 | `Get-Process java \| Stop-Process -Force` 后重试 |
| `jlink 失败，未生成 java.exe` | 模块清单缺失或 jmods 路径错 | 检查 `$JAVA_HOME\jmods` 目录存在；脚本兜底模块清单已覆盖常见场景 |
| `pyvenv.cfg 含本机绝对路径` | 系统 Python 注册了绝对路径 | 换 Python embeddable 包替换 `.venv`，或接受单机限制 |

---

## 2. 准备图标

electron-builder 配置 `win.icon: assets/icon.ico`，缺失会报错或用默认图标。

```powershell
# 在 electron 目录下
# 方式 A：用已有 PNG 转换（需 ImageMagick 或在线工具）
magick convert icon.png -define icon:auto-resize=256,128,64,48,32,16 assets/icon.ico

# 方式 B：从 Electron 默认图标复制占位（仅能跑通流程，正式发布需替换）
Copy-Item node_modules\electron\dist\resources\default_app.asar.unpacked\icon.png assets\icon.png
# 注意：.png 不能直接当 .ico 用，需转换或用工具生成

# 验证
Test-Path electron\assets\icon.ico   # → True
```

> 快捷方案：用 [icoconvert.com](https://icoconvert.com/) 上传 256×256 PNG 生成多尺寸 .ico。

---

## 3. 安装依赖并打包

```powershell
cd electron
npm install                 # 安装 electron + electron-builder
npm run dist                # 等价于 electron-builder --win
```

**预期产物：**
```
electron\dist\
  ├─ FastBox Setup 0.1.0.exe      # NSIS 安装包（约 150-250 MB）
  ├─ FastBox Setup 0.1.0.exe.blockmap
  └─ latest.yml                    # 自动更新元数据
```

**检查点：**
```powershell
# 安装包存在且体积合理
Get-Item dist\*"Setup"*.exe | Select-Object Name, @{N='SizeMB';E={[math]::Round($_.Length/1MB,1)}}
# SizeMB 应在 150-250 区间；若 <50MB 说明 extraResources 没打进（检查 build-runtime 是否跑过）
```

**常见问题：**

| 症状 | 原因 | 解决 |
|---|---|---|
| `icon.ico not found` | 步骤 2 未完成 | 补图标或临时注释 `win.icon` 字段 |
| 安装包 <50MB | runtime 组件未生成 | 回到步骤 1 跑 `build-runtime.ps1`，确认 `runtime\jre17` 和 `python-runtime\.venv` 存在 |
| `Cannot find module 'electron-builder'` | npm install 未跑或失败 | 删 `node_modules` 重装：`rm -r node_modules; npm install` |
| 打包卡住无输出 | 首次下载 Electron 二进制慢 | 设镜像 `setx ELECTRON_MIRROR https://npmmirror.com/mirrors/electron/` 后重试 |

---

## 4. 装机验证（关键）

目标：确认安装包在无开发环境的机器上能独立运行，三件套（Electron + Java 网关 + Python）自启动正常。

### 4.1 安装

```
1. 双击 FastBox Setup 0.1.0.exe
2. 选择安装目录（默认 C:\Users\<user>\AppData\Local\Programs\FastBox）
3. 完成后桌面/开始菜单出现 FastBox 快捷方式
```

### 4.2 首次启动验证

启动 FastBox，观察以下行为：

| 检查项 | 预期 | 排查 |
|---|---|---|
| 面板正常显示 | 搜索框可见、无白屏 | 查看日志 `%APPDATA%\FastBox\data\logs\electron-*.log` |
| Java 网关自启动 | 主进程日志出现 `[gateway] spawned` + 端口 8764 可达 | 日志查 `ensureGateway` 报错；确认 `resources\runtime\jre17\bin\java.exe` 存在 |
| Python 自启动 | 主进程日志出现 `[python] spawned` + 端口 8765 可达 | 日志查 `ensurePython` 报错；确认 `resources\runtime\python\.venv\Scripts\python.exe` 存在 |
| 搜索功能可用 | 输入"文件统计"等关键词有结果 | 网关未启动则无结果 |
| 数据目录正确 | `%APPDATA%\FastBox\data\fastbox.db` 被创建 | 路径错会写入 Program Files 被拒 |

**日志位置（打包模式）：**
```
%APPDATA%\FastBox\data\logs\electron-main.log
%APPDATA%\FastBox\data\logs\gateway.log
%APPDATA%\FastBox\data\logs\python.log
```

**快速验证命令（PowerShell）：**
```powershell
# 端口探测
Invoke-WebRequest http://127.0.0.1:8764/api/plugins -UseBasicParsing  # Java 网关
Invoke-WebRequest http://127.0.0.1:8765/health -UseBasicParsing        # Python

# 数据目录
Test-Path "$env:APPDATA\FastBox\data\fastbox.db"

# 查看主进程日志最后 30 行
Get-Content "$env:APPDATA\FastBox\data\logs\electron-main.log" -Tail 30
```

### 4.3 功能回归矩阵

| 功能 | 操作 | 预期 |
|---|---|---|
| 搜索联想 | 输入"base" | 出现"base64 编码"等 JS 插件结果 |
| JS 插件执行 | 点击"base64 编码" | 弹出详情面板显示编码结果 |
| Python 插件执行 | 搜索"文件统计" | 返回文件统计结果（走 Python → HTTP 8765） |
| SQL 脚本 | 切到 SQL Tab | 列出 5 个示例脚本；运行"数据库概览"出表格 |
| 搜索历史 | 空输入唤起面板 | 展示最近搜索关键词 |
| 收藏 | 搜索结果点星标 | 收藏 Tab 出现该项 |
| 参数表单 | 触发带必填参数的插件 | 弹出参数输入框 |

### 4.4 卸载验证

```
1. 控制面板卸载 FastBox
2. 检查 %APPDATA%\FastBox 是否残留（用户数据，卸载默认不删）
3. 任务管理器确认无遗留 java.exe / python.exe 进程
```

---

## 5. 路径契约（排错参考）

打包后 `process.resourcesPath` 解析逻辑见 `main.js` 的 `resolvePaths()`：

```
resourcesPath/
  ├─ runtime/
  │   ├─ gateway/fastbox-gateway.jar     # Java 网关 shaded jar
  │   ├─ jre17/bin/java.exe              # 精简 JRE 入口
  │   └─ python/
  │       ├─ main.py
  │       ├─ .venv/Scripts/python.exe    # 便携 Python 入口
  │       └─ plugins/                    # Python 内置插件
  └─ plugins/
      ├─ js/                             # JS 插件
      ├─ python/                         # Python 插件镜像
      └─ java/                           # Java 插件 jar
```

用户数据（SQLite + 日志）写入 `app.getPath('userData')`，即 `%APPDATA%\FastBox\data\`，因为 Program Files 无写权限。

**开发模式 vs 打包模式差异：**

| 路径 | 开发模式 | 打包模式 |
|---|---|---|
| 项目根 | `__dirname` 上跳 3 级 | 不适用 |
| Java 网关 | `java-gateway/target/fastbox-gateway.jar` | `resources\runtime\gateway\...` |
| JRE | 系统 PATH 的 `java` | `resources\runtime\jre17\bin\java.exe` |
| Python | 不自动启动（需手动跑） | `resources\runtime\python\.venv\Scripts\python.exe` |
| 数据目录 | `<root>\data\` | `%APPDATA%\FastBox\data\` |

---

## 6. 跨平台备注

当前流程以 Windows NSIS 为主：

- **macOS**：`npm run dist:mac` 产出 `.dmg`；需 `assets/icon.icns`；jlink/venv 需在 mac 上重跑 build-runtime.ps1（或等价 bash 脚本）；公证（notarization）未配置，首次打开需右键"打开"绕过 Gatekeeper。
- **Linux**：`npm run dist:linux` 产出 `.AppImage`；需在 Linux 上重跑运行时构建；AppImage 自带可执行权限。

跨平台运行时构建脚本目前仅 Windows 版（PowerShell），移植到 mac/Linux 需将 `jlink`/`python -m venv` 部分改写为 bash 等价命令。
