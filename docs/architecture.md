# FastBox 架构评审结论存档

> 评审对象：用户提交的《UTools 架构深度分析 + 自研同类工具架构设计文档》
> 评审时间：2026-08-14
> 存档状态：与 MVP 实际落地对照，标注「已采纳 / 部分采纳 / 待办」

---

## 一、原始设计回顾

用户提交的架构设计核心内容（五层架构 + 技术栈）：

```
┌─────────────────────────────────────────────┐
│ ① UI 交互层        Electron 悬浮搜索面板      │
├─────────────────────────────────────────────┤
│ ② 网关调度层       统一入口、搜索/动作分发      │
├─────────────────────────────────────────────┤
│ ③ 核心服务层       插件注册、SQL、收藏、历史    │
├─────────────────────────────────────────────┤
│ ④ 脚本执行层       JS / Java / Python 执行器   │
├─────────────────────────────────────────────┤
│ ⑤ 系统底层适配层    热键、托盘、文件、剪贴板     │
└─────────────────────────────────────────────┘
```

- 技术栈提案：Electron + Java 17 SpringBoot + Python 3.10+ + SQLite
- 核心差异化：同时支持 JS / Java / Python / SQL 四种扩展类型
- 原始文档含：核心数据流、跨语言调用模块规范、插件系统规范、跨平台打包方案、目录结构、MVP 开发落地顺序 8 步

## 二、评审结论（6 条关键优化建议）

| # | 建议 | 状态 |
|---|------|------|
| 1 | **Javalin 轻量 HTTP 服务替代 SpringBoot**：SpringBoot 冷启动 2-4s、内存 100MB+，对常驻快捷启动工具不可接受；Javalin fat-jar 启动 <1s | ✅ 已采纳（java-gateway 使用 Javalin 6.3.0） |
| 2 | **Python FastAPI 常驻服务替代 stdio 进程池**：进程池方案每次执行 fork 解释器开销大、复用复杂度高；解释器常驻内存后延迟降至 10-50ms | ✅ 已采纳（python-runtime/main.py，端口 8765） |
| 3 | **SQLite 仅 Java 网关直连**：Electron/Python 一律走 HTTP，避免多进程文件锁冲突与 WAL 竞争 | ✅ 已采纳（架构硬约束：SQLite 单写者） |
| 4 | **"兼容 UTools 插件"降级为"借鉴 UTools 规范"**：UTools 插件生态基于其私有 SDK，MVP 阶段全兼容成本高、收益低；先定义自有 plugin.json 规范 | ✅ 已采纳 |
| 5 | **进程生命周期自愈**：Electron 启动/运行中探测网关健康，失联自动拉起（ensureGateway 轮询 /health） | ✅ 已采纳（main.js 实现） |
| 6 | **统一 JSON 契约 + 错误码规范**：跨语言调用固定 `keyword/args/userConfig` 入参与 `code/data/message/cost_ms` 出参，避免各层自定义格式 | ✅ 已采纳（python-runtime 契约 + ApiRoutes 实现） |

## 三、落地决策记录

评审后实际执行的架构调整，与原始提案的差异：

### 3.1 技术栈调整
| 模块 | 原始提案 | 落地选择 | 原因 |
|------|----------|----------|------|
| 网关 | Java 17 SpringBoot | Java 17 Javalin 6.3.0 | 冷启动 <1s、内存占用低 |
| Python 调用 | stdio 进程池 | FastAPI 常驻 HTTP(1.1) | 解释器复用、延迟 10-50ms |
| 数据存储 | SQLite（多端） | SQLite 仅网关直连（WAL） | 单写者避免锁冲突 |
| 插件执行 | 完整 UTools 兼容 | 自有 plugin.json 规范 | MVP 聚焦，后续按需演进 |

### 3.2 路径与部署
- **FastBoxPaths**：所有路径基于 jar 位置向上跳 3 级解析，不依赖 `user.dir`，保证从任意目录启动路径一致。
- **数据目录**：`FastBox/data/`（local.db、cache、logs），与代码目录分离。
- **端口约定**：网关 `127.0.0.1:8764`，Python 运行时 `127.0.0.1:8765`，均仅绑定回环。

### 3.3 数据库设计（7 表）
`t_config`、`t_shortcut`、`t_plugin`（kind CHECK IN js/java/python）、`t_search_history`、`t_favorite`、`t_sql_script`、`t_exec_log`
- WAL 模式：`PRAGMA journal_mode=WAL`、`busy_timeout=5000`、`foreign_keys=ON`

### 3.4 搜索调度优先级（SearchService）
内置工具（calc/files/help）→ SQL 脚本 → 插件（前缀/包含匹配）→ 收藏 → 本地文件（`file:` 前缀或路径分隔符触发，深度 3、限 8 条）

### 3.5 安全护栏
- 计算表达式白名单字符校验（exp4j）
- SQL 执行 DDL/DML 分离，SELECT 返回表格；禁止 `drop t_config` 等危险操作
- 文件搜索限深限量，插件数量上限保护

## 四、非阻塞修改点（原评审补充项）

| 项 | 说明 | 状态 |
|----|------|------|
| GPU 关闭 | 沙箱/远程桌面 GPU 不可用导致 Electron 崩溃 | ✅ 已完成：`disableHardwareAcceleration()` + `--disable-gpu --disable-gpu-compositing --in-process-gpu --no-sandbox`（须在 whenReady 之前调用） |
| 自动更新 | 桌面应用升级通道（如 electron-updater / 自建更新服务） | ⏳ 待办 |
| 错误恢复 | 网关异常自愈、插件崩溃隔离、执行失败日志留痕 | ✅ 已完成：网关自愈（ensureGateway）；JS 插件 try/catch + 错误 toast；Java/Python 网关内异常隔离 + `t_exec_log` 留痕；`POST /api/exec-log` 供 Electron 主进程上报 JS 插件记录；`GET /api/exec-logs` 查询入口 |
| 日志策略 | 各层日志路径统一、轮转、级别可配 | ✅ 已完成：`data/logs/{gateway,python,electron}.log` 统一目录；logback / RotatingFileHandler / 自定义文件轮转 10MB 保留 7 份；级别均受 `FASTBOX_LOG_LEVEL` 环境变量控制 |

## 五、MVP 落地现状对照（2026-08-14 验证通过）

- ✅ Python 运行时：`/health` 返回 2 个插件
- ✅ Java 网关：`/health` 返回 `{"status":"ok","python":"up"}`
- ✅ 插件注册：扫描 `plugin.json` 幂等写入 t_plugin
- ✅ 搜索：`/api/search?q=文件统计` 命中 Python 插件
- ✅ 动作：计算器 `1+2*3=7`、打开文件、执行 Python 插件（时间戳返回当前时间）
- ✅ SQL：保存脚本 + 执行查询返回表格
- ✅ UI：680×480 暗色悬浮面板，网关就绪徽章正常（截图 data/logs/panel.png）
- ✅ **插件参数通道**：`args_schema` 声明 → 搜索返回 → 前端参数表单 → 后端校验透传 → Python 插件执行（截图 data/logs/panel-args-full.png）

### 插件参数通道契约（新增）

```json
// plugin.json
{
  "name": "文件统计",
  "description": "统计指定目录的文件数量、总大小与扩展名分布",
  "keywords": ["文件统计", "file-stats", "fstats"],
  "args_schema": [
    {"name": "目录路径", "type": "string", "required": true}
  ]
}
```

调用链路：
1. 搜索 `/api/search?q=文件统计` → `payload.argsSchema` 返回 schema
2. 前端识别必填参数 → 弹出 `argOverlay` 参数输入表单
3. 用户确认 → `POST /api/action {"action":"plugin","payload":{"pluginId":1,"keyword":"文件统计","kind":"python","args":["D:/xxx"]}}`
4. Java 网关校验必填 → 构造 `args = [插件目录名, ...用户参数]` → 调用 Python `/execute`
5. Python 插件 `run(keyword, args[1:], user_config)` 执行并返回结果

**设计要点**：
- 仅当 schema 中存在 `required=true` 的字段时才弹表单；全可选参数直接执行，避免打扰
- 后端 `missingRequired()` 做兜底校验，防止绕过 UI 直接调用
- 插件目录名（path 的 basename）替代原先写死的脚本绝对路径，语义清晰

## 六、已知缺口与后续路线

1. **~~插件参数通道~~**：已实现并验证（P1 → ✅）
2. **~~JS 插件执行器~~**：已实现并验证（P1 → ✅）。详见第七节
3. **~~Java 插件执行器~~**：已实现并验证（P2 → ✅）。详见第八节
4. **~~插件级沙箱隔离~~**：进程级隔离已落地（P2 → ✅），剩余更细粒度文件/网络限制为远期可选
5. **架构文档归档关联**：README 中架构硬约束需与本文档同步（P2）
6. **自动更新与日志轮转**：见第四节待办（P2）
7. **跨平台打包**：electron-builder + 网关/Python 运行时随包分发（P3，MVP 后）

## 七、JS 插件执行器实现要点

### 设计决策

JS 插件**直接在 Electron 主进程内执行**（不绕 Java 网关）：
- Electron 本身跑在 Node/V8 上，无需 HTTP 转发
- 搜索仍走 Java 网关（`PluginScanner` 启动时扫描 `plugins/js/` 写入 `t_plugin`）
- 执行时渲染层根据 `payload.kind === 'js'` 路由到本地 IPC，避免跨语言开销

### 插件契约

```
plugins/js/<name>/
  plugin.json    { name, keywords: [...], description, kind: "js", args_schema?: [...] }
  main.js        module.exports = { run(keyword, args, userConfig) -> { detail?, toast? } }
```

- `keywords` 支持 JSON 数组（统一格式），`PluginScanner.joinKeywords` 已兼容字符串格式
- `run` 返回值统一为 `{ detail?: {title, content}, toast?: string, data?: any }`
- 主进程通过 `delete require.cache` 实现热重载（开发友好）

### 渲染层调用链

```
搜索 base64 → payload.kind="js" → 点击 → openArgForm（必填参数）
                                        ↓
                                  用户填入 + 确认
                                        ↓
                            doAction 检测 kind="js"
                                        ↓
                   window.fastbox.executeJs(payload)  ← IPC invoke
                                        ↓
                主进程 ipcMain.handle('plugin:execute-js')
                                        ↓
                executeJsPlugin → require(entryFile).run(...)
                                        ↓
                       返回 {detail, toast} → showDetail
```

### 关键路径

- 主进程：`electron/src/main/main.js` 中 `JS_PLUGINS_DIR = path.join(__dirname, '..', '..', '..', 'plugins', 'js')`（注意跳 3 级到 FastBox 根）
- 暴露：`preload.js` 中 `executeJs(payload) → ipcRenderer.invoke('plugin:execute-js', payload)`
- 路由：`app.js` 中 `doAction` 根据 `payload.kind === 'js'` 优先走本地执行

### 测试用例

`plugins/js/base64/` 编解码工具已落地：
- 关键词：`base64,编码,解码,encode,decode,b64`
- 入参：待处理文本（必填）
- 模式：关键词含「解码」则解码，否则编码
- 验证截图：`data/logs/panel-js4.png` 显示完整搜索→参数表单→执行→结果链路

---

## 八、Java 插件执行器实现要点

### 设计决策

Java 插件在**网关进程内用独立 ClassLoader 加载执行**（不走 Python HTTP）：
- Java 网关本身就是 JVM，无需再启解释器
- 搜索仍走 Java 网关（`PluginScanner` 启动时扫描 `plugins/java/` 写入 `t_plugin`）
- 插件 jar 自带 SPI 接口 `com.fastbox.plugin.spi.FastBoxPlugin`，网关通过反射调用，避免编译期耦合
- 执行结果遵循统一 JSON 契约：`{code, data: {detail?: {title, content}}, message}`

### 插件契约

```
plugins/java/<name>/
  plugin.json    { name, keywords: [...], description, kind: "java", main: "...", args_schema?: [...] }
  <name>.jar     包含 FastBoxPlugin 接口类 + 实现类（纯 JDK 示例 sha256）
```

- `main` 字段声明入口类全限定名，如 `com.fastbox.plugin.sha256.Sha256Plugin`
- SPI 接口签名：`Map<String, Object> run(String keyword, List<String> args, Map<String, Object> userConfig)`
- 返回契约：`code=0` 成功，`data.detail` 会展示在面板详情弹窗；`code≠0` 时 `message` 作为失败提示

### 关键路径

- 扫描：`PluginScanner.scanJava()` 扫描 `plugins/java/<name>/plugin.json`，`t_plugin.kind='java'` 已预留
- 插件数量上限保护：`PluginScanner` 注册新增插件前检查 `t_plugin` 总数，超过 200 个跳过并警告（已存在插件更新不受影响）
- 加载器：`java-gateway/.../plugin/JavaPluginLoader.java`
  - 默认 ClassLoader 模式：缓存 `LoaderEntry`（jar lastModified + 独立 `URLClassLoader` + 入口类），jar 变更自动重建，低延迟
  - Process 模式：插件 `plugin.json` 声明 `"sandbox": "process"` 时，fork 独立 JVM 子进程执行，通过 stdout 读取 JSON 结果
  - 两种模式 parent 均使用 `ClassLoader.getPlatformClassLoader()`，避免直接访问网关内部类
- 子进程启动器：`com.fastbox.plugin.launcher.PluginLauncher`，编译进网关 jar；子进程 classpath = 网关 jar + 插件 jar
- 用户参数通过临时 JSON 文件（`--args-file`）传递，避免 Windows 命令行引号/空格转义问题
- 执行：`ActionService.runJavaPlugin()` 调用 loader → 反射 `run(...)` → 统一返回格式 → 前端 `detail` 弹窗
- 前端：`app.js` KIND_ICON/KIND_LABEL 增加 `java: 'Ja'` / `'Java'`，`uiTest=java` 自动化测试已加入

### 安全边界

| 层级 | 机制 | 状态 |
|------|------|------|
| ClassLoader 隔离 | 每个插件独立 URLClassLoader，parent 为平台类加载器，不暴露网关业务类 | ✅ 已实现（默认） |
| 进程级沙箱 | `sandbox: "process"` 启用独立 JVM 子进程，插件 `System.exit`/死循环/异常均不影响网关 | ✅ 已实现 |
| 超时保护 | 子进程模式 30 秒超时，强制 `destroyForcibly()` | ✅ 已实现 |
| 文件/网络限制 | 当前依赖 JVM 默认策略；如需更细粒度控制，可后续引入自定义 `Policy` 或子进程 JVM 参数 | ⏳ 远期 |

### 执行留痕链路

- 数据表：`t_exec_log(kind, keyword, action, plugin_name, cost_ms, status, message, created_at)`
- 网关内执行（Python/Java）：`ActionService.runPlugin()` 调用 `logPluginAction()` 直接落库
- JS 插件（Electron 主进程）：主进程 `executeJsPlugin()` 执行后，调用 `POST /api/exec-log` 异步上报
- 查询入口：`GET /api/exec-logs?limit=&kind=`，支持按 `js/python/java` 过滤
- 失败场景：插件抛异常 / System.exit / 死循环超时 / JS throw → status=`fail`，message 记录错误信息，网关与主进程均不崩溃

### 日志策略

| 层 | 实现 | 文件 | 轮转 | 级别配置 |
|----|------|------|------|----------|
| Java 网关 | logback-classic | `data/logs/gateway.log` | 按天 + 10MB，保留 7 份（`SizeAndTimeBasedRollingPolicy`） | `FASTBOX_LOG_LEVEL` |
| Python 运行时 | `logging.handlers.RotatingFileHandler` | `data/logs/python.log` | 10MB 保留 7 份 | `FASTBOX_LOG_LEVEL` |
| Electron 主进程 | 自定义 `electron/src/main/logger.js` | `data/logs/electron.log` | 超过 10MB 重命名为 `.1` | `FASTBOX_LOG_LEVEL` |

### 测试用例

`plugins/java/sha256/` 摘要工具已落地：
- 关键词：`sha256,摘要,sha,哈希,hash,校验`
- 入参：文本（必填）
- 模式：关键词含「校验」且输入 `原文=期望摘要` 时校验；否则计算摘要
- 验证截图：`data/logs/panel-java.png` 显示完整搜索 → 参数表单 → 执行 → 结果链路
- HTTP 接口结果：`Hello FastBox` → `4133109adf964da660ff5177171834e75d035c68793d39f8fae97c28f52d7fa3`

`plugins/java/bad/` 沙箱验证插件（仅测试用，不进入推荐列表）：
- 默认行为：抛出运行时异常
- 关键词含 "exit"：调用 `System.exit(1)`
- 关键词含 "loop"：死循环
- 验证：三种危险行为均被隔离，网关仍可用，正常 sha256 插件可继续执行

## 九、打包与更新策略（2026-08-14 评估）

> 状态：✅ 方案评估与骨架落地（P3）；❌ 完整打包与更新服务器部署未执行。
> 关联产物：`electron/electron-builder.yml`（骨架）、PRD M10。

### 9.1 打包组件清单与运行时依赖

| 组件 | 形态 | 运行时依赖 | 打包策略 |
|------|------|-----------|---------|
| Electron UI | `electron/`（asar） | 无（自带 Chromium/Node） | electron-builder → NSIS/dmg/AppImage |
| Java 网关 | `java-gateway/target/fastbox-gateway.jar`（Java 17 shaded jar） | JRE 17+（当前依赖系统 `java`） | 内置 jlink 精简 JRE 17（`resources/runtime/jre17`） |
| Python 运行时 | `python-runtime/main.py` + FastAPI/uvicorn | Python 3.11+（当前依赖系统 `python`） | 内置便携 Python（拷贝 `.venv`，含解释器+site-packages） |
| 插件 | `plugins/{js,python,java}` | 各运行时 | 随包分发初始插件，用户可扩展 |

### 9.2 决策记录

1. **打包工具选型：electron-builder**（非 electron-forge）。
   - 理由：extraResources 机制天然适配「Electron 外壳 + 外部运行时」形态；NSIS 支持可选安装目录与桌面快捷方式；三平台统一配置。
2. **Java 网关随包策略：jlink 精简 JRE**，而非要求用户安装 JDK、也非 GraalVM 原生镜像。
   - jlink 命令（骨架期先记录，正式打包前落成脚本 `scripts/build-runtime.ps1`）：
     ```
     jlink --add-modules java.base,java.sql,java.logging,java.naming,java.management,jdk.unsupported,jdk.crypto.ec \
           --strip-debug --no-man-pages --no-header-files \
           --output <projectRoot>/runtime/jre17 <JAVA_HOME>/jmods
     ```
   - 模块清单依据：SQLite JDBC（java.sql）、logback（java.logging）、Jetty（java.naming/java.management）、反射内部（jdk.unsupported）。正式打包前用 `jdeps --print-module-deps` 复核。
   - 体积预估 40-60MB（对比完整 JRE ~300MB）。
3. **Python 运行时随包策略：便携发行（拷贝 `.venv`）**，不用 PyInstaller。
   - 硬约束：Python 插件由 FastAPI `importlib` **动态加载用户自定义模块**，PyInstaller 单文件模式无法覆盖运行期新增插件；必须保留可执行 Python 解释器。
   - 便携化要点：`.venv/Scripts/python.exe` 为绝对路径解释器，跨机分发前需用 `venv --copies` 重新生成或改用 embeddable 发行版 + 显式 `site-packages` 路径。
   - 体积预估 30-80MB（fastapi/uvicorn/pydantic 及二进制依赖）。
4. **数据目录位置：打包版必须迁移到 `app.getPath('userData')`**。
   - 现状：`data/` 位于项目根目录，开发模式可行；安装到 `C:\Program Files` 后**无写权限**。
   - 改造点（P3 实施，骨架阶段仅记录）：主进程启动时设置 `FASTBOX_DATA_DIR=<userData>/data` 环境变量传给网关/Python 子进程；`FastBoxPaths` 增加 env 覆盖优先。
5. **运行时路径解析：打包模式从 `process.resourcesPath` 取外部组件**。
   - 现状：`main.js` 用 `path.join(__dirname,'..','..','java-gateway','target','fastbox-gateway.jar')`（开发模式 3 级向上）。
   - 改造点（P3 实施）：`const isPacked = app.isPackaged;` 打包时改用 `process.resourcesPath/runtime/gateway/fastbox-gateway.jar` 与 `process.resourcesPath/runtime/jre17/bin/java(.exe)`；**Python 服务打包版需由主进程新增 spawn 自拉起**（当前开发模式为手动启动）。
6. **自动更新选型：electron-updater + generic provider（自建静态更新服务器）**。
   - 理由：Java/Python/插件全部在 extraResources → **整包替换即全量更新**，electron-updater 天然支持（下载新安装包 → 校验 → 静默替换）；无需开发自有更新协议。
   - 差分说明：blockmap 差分仅覆盖 asar/app 主文件；extraResources 变化会导致下载完整新包（体积 ~200MB，P3 可接受，后续可评估分块下载）。
   - 备选方案对比：GitHub Releases（provider: github）——FastBox 暂无公开仓库，暂不采用；自建 HTTP 静态目录放 `latest.yml + 安装包 + blockmap` 即可，成本最低。
   - 更新服务器占位：`electron-builder.yml` 中 `publish.url = https://example.com/fastbox/releases`，部署时替换。
7. **版本契约：整包单一版本号**（Electron app version 同步），Java/Python 组件不单独发布更新；`t_exec_log` 等数据契约保持向后兼容，更新安装不丢用户数据（数据在 userData 目录，安装替换不触碰）。

### 9.3 风险与对策

| 风险 | 等级 | 对策 |
|------|------|------|
| 安装目录只读导致 data/ 不可写 | 高 | 数据目录迁移 userData（9.2-4），P3 首要改造项 |
| Python 便携运行时体积大/路径绝对化 | 中 | `.venv --copies` 重建 + 体积复核；必要时 embeddable 发行版 |
| jlink 缺模块导致网关启动失败 | 中 | 正式打包前 `jdeps` 复核模块清单；打包后冒烟测试必跑 |
| extraResources 变化无法差分 | 低 | 接受全量下载；后续评估分块/增量 |
| 更新服务器被篡改 | 低 | electron-updater 内置签名校验；配置 Windows 代码签名证书（P3 前置） |

### 9.4 验收清单（骨架阶段）

- [x] `electron/electron-builder.yml` 骨架落地（extraResources 含 jre/python/gateway/plugins）
- [x] 打包与更新决策记录（本节）
- [ ] jlink JRE 生成脚本 + jdeps 模块复核
- [ ] 便携 Python 跨机验证（`venv --copies`）
- [ ] main.js 打包路径改造（resourcesPath + userData + Python 自拉起）
- [ ] 图标资产补充（icon.ico / icon.icns）
- [ ] `npx electron-builder --win` 实际打包冒烟
- [ ] 更新服务器部署 + latest.yml + 升级链路验证

---

*本文件为 FastBox 项目架构决策的唯一存档源，后续架构变更须在此追加记录。*
