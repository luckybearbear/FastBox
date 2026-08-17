# FastBox 产品需求文档（PRD）

> 版本：v0.2（MVP 对齐版）
> 更新时间：2026-08-14
> 状态：MVP 核心链路已落地验证，本文档同步记录已实现现状与后续规划
> 关联文档：`docs/architecture.md`（架构决策存档）、`README.md`（快速启动）

---

## 一、产品概述

### 1.1 一句话定位

**FastBox 是一款个人自用的跨平台桌面快捷启动工具**（UTools 风格），核心差异化在于**同时打通 JS / Java / Python / SQL 四类扩展**，让不同语言生态的工具都能通过统一的搜索框调用。

### 1.2 核心价值

| 价值点 | 说明 |
|--------|------|
| 统一入口 | 一个悬浮搜索框承载计算、文件、脚本、SQL、插件等全部能力 |
| 多语言扩展 | 插件生态不绑定单一语言：Python 插件（数据处理）、JS 插件（前端工具）、Java 插件（企业级 API）各取所长 |
| 低延迟 | 网关冷启动 <1s、Python 解释器常驻，日常操作延迟控制在 10-50ms 级 |
| 数据可控 | SQLite 本地存储，无云依赖，脚本与数据全部留本机 |

### 1.3 产品形态

- 全局热键唤起的悬浮搜索面板（680×480 暗色）
- 系统托盘常驻
- 浏览器/终端之外的「第三类启动器」

---

## 二、背景与问题

### 2.1 痛点

1. **工具碎片化**：计算器、文件搜索、脚本执行、SQL 查询分散在不同应用，切换成本高。
2. **插件语言绑定**：主流启动器（如 UTools）插件生态以 JS 为主，Python/Java 开发者无法用熟悉语言快速扩展。
3. **企业场景缺失**：Java 后端开发者（如 MES 领域）常需要调用企业级 API，现有启动器难以承载。

### 2.2 解决方案

以轻量 Java 网关（Javalin）为枢纽，SQLite 为数据层，Electron 为 UI，Python FastAPI 常驻服务为脚本执行层，构建一条**统一 JSON 契约**的多语言插件通道。

---

## 三、目标与非目标

### 3.1 目标（MVP 范围）

- G1：可常驻运行的桌面启动器（热键 + 托盘 + 搜索面板）。
- G2：搜索调度覆盖「内置工具 → SQL 脚本 → 插件 → 收藏 → 本地文件」。
- G3：插件系统支持 **Python / JS** 两类可执行插件（Java 预留）。
- G4：插件参数通道：声明式参数表单 + 必填校验 + 透传执行。
- G5：SQL 脚本保存与执行（SELECT 返回表格）。

### 3.2 非目标（明确不做）

- ✗ UTools 插件生态全兼容（仅借鉴 plugin.json 规范）。
- ✗ 云同步、账号体系、插件市场。
- ✗ 移动端 / Web 端。
- ✗ MVP 阶段不做 Java 插件执行器与跨平台安装包（见里程碑）。

---

## 四、目标用户与使用场景

### 4.1 用户画像

**个人开发者 / 技术爱好者**（首要用户即产品作者本人）：
- 熟悉 Java / Python / JS 至少一种语言；
- 高频使用搜索框效率工具；
- 有自定义脚本与内部工具聚合诉求。

### 4.2 核心场景

| 场景 | 描述 | 示例 |
|------|------|------|
| S1 快速计算 | 输入表达式立即得结果 | `1+2*3` → `7` |
| S2 文件速开 | 输入文件名或路径快速打开 | `file:D:/xxx` |
| S3 插件调用 | 关键词唤起插件并传参 | 「文件统计」→ 填目录 → 输出统计 |
| S4 JS 工具 | 本地无网络的小工具 | 「base64」→ 编码/解码 |
| S5 SQL 查询 | 执行已存脚本查询 | `scripts/sql/` 内脚本 |
| S6 收藏/历史 | 高频项固定、历史可回溯 | 收藏常用命令 |

---

## 五、功能需求详述

> 优先级：P0 = MVP 必须 / P1 = 重要 / P2 = 增强 / P3 = 远期
> 状态：✅ 已实现 / ⏳ 待办

### FR-01 悬浮搜索面板（P0 ✅）

- 全局热键唤起/隐藏悬浮面板（默认建议 `Alt+Space`，可配置）。
- 680×480 暗色 UI，输入框 + 结果列表 + 详情区。
- 结果项展示：图标/类型徽章、标题、副标题（含参数要求提示）、快捷键提示。
- 上下键选择、回车执行、Esc 关闭。
- 面板可拖动；输入清空后自动隐藏。

### FR-02 搜索调度（P0 ✅）

搜索优先级（SearchService 实现）：

```
内置工具（calc / files / help）
  → SQL 脚本
  → 插件（前缀 / 包含匹配）
  → 收藏
  → 本地文件（file: 前缀或路径分隔符触发，深度 3，限 8 条）
```

- 匹配逻辑：前缀优先于包含匹配。
- 内置工具：计算器（exp4j 白名单校验）、文件打开、帮助。

### FR-03 插件系统——通用（P0 ✅）

- `plugin.json` 规范：`name / description / keywords / kind / args_schema`。
- `keywords` 支持 JSON 数组（兼容逗号字符串，`PluginScanner.joinKeywords` 统一处理）。
- 启动时扫描 `plugins/{js,python,java}` 幂等注册到 `t_plugin`（kind CHECK 约束）。
- 搜索命中返回 `payload.argsSchema` 供前端渲染参数表单。

### FR-04 Python 插件执行器（P0 ✅）

- 链路：`Electron → Java 网关 HTTP → Python FastAPI /execute → 插件模块`。
- Python 解释器**常驻内存**（FastAPI，端口 8765），不走 stdio 进程池。
- Java 调用 Python 强制 `HTTP/1.1`（避免 h2c 升级被 uvicorn h11 拒绝）。
- 插件契约：`run(keyword, args, user_config) → {code, data, message, cost_ms}`。
- 已落地示例：`file-stats`（文件统计，带参数）、`timestamp`（时间戳，无参）。

### FR-05 JS 插件执行器（P0 ✅）

- 搜索走 Java 网关统一注册；**执行走 Electron 主进程本地 IPC**（不绕 HTTP）。
- 契约：`main.js` 导出 `run({keyword, args, userConfig}) → {detail?, toast?, data?}`。
- 主进程 `delete require.cache` 实现热重载（改插件无需重启 Electron）。
- 渲染层根据 `payload.kind === 'js'` 路由本地 IPC（`window.fastbox.executeJs`）。
- 已落地示例：`base64` 编解码（关键词含「解码」则解码，否则编码）。

### FR-06 插件参数通道（P0 ✅）

- `args_schema` 声明参数：`{name, type, required, hint?, default?}`。
- **仅当存在必填参数时弹出参数表单**；全可选参数直接执行（避免打扰）。
- 后端 `missingRequired()` 兜底校验，防止绕过 UI 直接调用。
- 调用链：`搜索 → 弹表单 → 填参确认 → POST /api/action → Java 校验 → args=[插件目录名, ...用户参数] → Python/JS 执行`。

### FR-07 SQL 脚本（P0 ✅）

- 保存脚本至 `scripts/sql/`，注册进搜索。
- 执行 SELECT 返回表格展示；DDL/DML 分离；禁止危险操作（如 drop 关键表）。
- 注：`scripts/sql/` 目录当前为空，示例脚本待补（P2）。

### FR-08 搜索历史与收藏管理（P0 ✅）

- `t_favorite` / `t_search_history` 表已建，UI 已完整落地。
- **历史联想**：搜索时自动记录关键词（去重 + 100 条上限）；面板唤起空输入时展示最近 20 条历史，点击即搜索。
- **收藏管理**：搜索结果项右侧星标按钮一键收藏/取消；收藏面板（底部 Tab 切换）列出所有收藏，支持删除；点击收藏项直接重新执行（兼容 JS 插件 IPC 和参数表单插件）。
- **收藏去重**：前端 `action|JSON.stringify(payload)` 复合 key 追踪星标状态；后端 `t_favorite` 存 `name/action/payload(JSON)`。
- **API 端点**：`GET /api/history?limit=`、`GET /api/favorites`、`POST /api/favorites`、`POST /api/favorites/delete`。

### FR-09 SQL 可视化与脚本管理（P0 ✅）

- **结果表格渲染**：后端返回 `columns` / `rows` / `cost_ms`，前端用 DOM API 构建 `<table>`（表头 sticky、斑马纹、横向滚动）；`showSqlTable()` 与 `showDetail()` 双模式互斥切换。
- **复制 CSV**：单元格 hover 选中态 + 一键 `navigator.clipboard.writeText`，字段含逗号/换行/引号时自动加引号转义。
- **脚本管理面板**：底部 toolbar 新增「SQL」Tab，列出 `t_sql_script` 全部脚本，含运行/编辑/删除按钮；新建/编辑共用 `sqlOverlay` 编辑器（名称/描述/SQL 文本域 + Ctrl+Enter 保存）。
- **scripts/sql/ 目录扫描**：新增 `SqlScriptScanner`（参照 `PluginScanner`），启动时扫描 `scripts/sql/*.sql`，解析首部 `-- name:` / `-- description:` 注释，幂等注册到 `t_sql_script`（同名内容相同跳过，不同则覆盖以文件为准）。
- **示例脚本**（`scripts/sql/`）：`exec-top10.sql`（执行日志 Top10）、`search-stats.sql`（搜索历史统计）、`plugin-list.sql`（插件清单）、`favorite-list.sql`（收藏清单）、`db-overview.sql`（数据库概览）。
- **API 端点**：`POST /api/sql/delete`（删除脚本，与 `/api/sql/save` 形成完整 CRUD）。
- **uiTest**：新增 `sqlpanel`（截图 SQL 管理面板）、`sql`（截图 SQL 表格结果）。

### FR-10 Java 插件执行器（P2 ✅）

- 已实现：独立 ClassLoader 加载 `plugins/java/<name>/<name>.jar`。
- SPI 规范：`com.fastbox.plugin.spi.FastBoxPlugin`，签名 `Map run(String keyword, List<String> args, Map userConfig)`，插件 jar 自带接口副本。
- 热重载：缓存 `LoaderEntry`（jar lastModified + loader + 主类），jar 变更后自动重建 loader。
- 异常隔离：插件执行异常转换为统一 `{code, message}`，不拖垮网关。
- 已落地示例：`plugins/java/sha256/`（SHA-256 摘要与校验）。
- 前端路由：`KIND_ICON/KIND_LABEL` 增加 `java`；`uiTest=java` 自动化测试已支持。
- 待办：进程级沙箱隔离为 P2（见 §十 风险）。

### FR-11 系统底层能力（P0/P2）

| 能力 | 状态 | 说明 |
|------|------|------|
| 全局热键 | ✅ | 主进程注册，唤起面板 |
| 系统托盘 | ✅ | 常驻 + 退出入口 |
| 网关自愈 | ✅ | `ensureGateway` 探测 `/health`，失联自动拉起 |
| GPU 兼容 | ✅ | 沙箱/远程桌面：`disableHardwareAcceleration` + `--disable-gpu* --no-sandbox` |
| 日志 | ✅ | `data/logs/{gateway,python,electron}.log` 统一目录；logback 按天+10MB 轮转保留 7 份；Python RotatingFileHandler 10MB/7 份；Electron 10MB 轮转；级别均受 `FASTBOX_LOG_LEVEL` 控制 |
| 自动更新 | ⏳ 方案已定 | electron-updater + generic 自建更新服务器（P2）；决策见 architecture.md 第九节 |
| 跨平台打包 | ✅ | electron-builder 配置 + main.js 路径改造（`process.resourcesPath` 解析）+ jlink JRE + portable Python（`scripts/build-runtime.ps1`）+ Python 自拉起；`electron/electron-builder.yml` 已落地。完整安装包产出待跑（依赖图标 + jlink + venv 重建，可通过 `npm run dist` 完成） |

### FR-12 开发者体验（P1 ✅）

- 调试钩子（`FASTBOX_*` 环境变量）：
  - `FASTBOX_SHOW_ON_START=1`：启动即显示面板。
  - `FASTBOX_CAPTURE_PNG=<path>`：延迟截屏保存 PNG 并退出（`FASTBOX_CAPTURE_DELAY` 控制延迟）。
  - `FASTBOX_UI_TEST=args|js|java|search|favorites|sqlpanel|sql`：渲染层自动搜索插件并触发参数表单/收藏面板/脚本面板/表格结果（自动化回归）。
- 渲染层 `console.log/warn/error` 转发到主进程日志，便于无头调试。

---

## 六、非功能需求（NFR）

| 编号 | 类别 | 要求 |
|------|------|------|
| NFR-1 | 性能 | 网关冷启动 <1s；插件执行延迟 10-50ms 级；搜索响应 <100ms |
| NFR-2 | 资源占用 | 网关内存优先低占用（Javalin 替代 SpringBoot 已达成） |
| NFR-3 | 安全 | SQLite 单写者（仅网关直连）；表达式白名单；SQL 危险操作拦截；插件数量上限保护；文件搜索限深限量；Java 插件基础 ClassLoader 隔离 + `sandbox: "process"` 进程级沙箱 |
| NFR-4 | 可靠性 | 网关失联自动拉起；Python/JS/Java 插件执行异常隔离；Java 插件子进程 30s 超时保护；执行失败日志留痕 |
| NFR-5 | 跨平台 | Windows 优先（当前开发环境），架构保持 macOS/Linux 可移植 |
| NFR-6 | 数据 | 数据与代码目录分离（`data/`）；WAL 模式 + busy_timeout=5000 |

---

## 七、数据模型与接口契约

### 7.1 数据库表（7 表）

`t_config`、`t_shortcut`、`t_plugin`（kind CHECK IN js/java/python，含 args_schema 列）、`t_search_history`、`t_favorite`、`t_sql_script`、`t_exec_log`

- 幂等迁移：`ALTER TABLE ... ADD COLUMN` + 捕获 duplicate column 异常。

### 7.2 统一 JSON 契约

```json
// 请求
{ "keyword": "...", "args": ["..."], "userConfig": {} }

// 响应
{ "code": 0, "data": {}, "message": "ok", "cost_ms": 12 }
```

### 7.3 关键端点

| 端点 | 用途 |
|------|------|
| `GET /health` | 网关健康检查（含 python 状态） |
| `GET /api/search?q=` | 统一搜索（返回 argsSchema） |
| `POST /api/action` | 动作执行（plugin / sql / 内置） |
| `POST /api/exec-log` | Electron 主进程上报 JS 插件执行留痕 |
| `GET /api/exec-logs?limit=&kind=` | 执行记录查询入口 |
| `GET /api/history?limit=` | 搜索历史列表（去重，最近 N 条） |
| `GET /api/favorites` | 收藏列表（含原始 action+payload） |
| `POST /api/favorites` | 添加收藏（name/action/payload） |
| `POST /api/favorites/delete` | 删除收藏（by id） |
| `POST /api/sql/execute` | 直接执行 SQL（带结构化 columns/rows/cost_ms） |
| `GET /api/sql/scripts` | SQL 脚本列表（含 sql_text） |
| `POST /api/sql/save` | 保存/更新 SQL 脚本（name/description/sql） |
| `POST /api/sql/delete` | 删除 SQL 脚本（by id） |
| `POST /execute`（Python 8765） | Python 插件执行 |

### 7.4 插件契约速览

| 类型 | 目录 | 入口 | 执行方 |
|------|------|------|--------|
| Python | `plugins/python/` | `main.py` + `plugin.json` | Java → FastAPI `/execute` |
| JS | `plugins/js/<name>/` | `main.js` + `plugin.json` | Electron 主进程 IPC |
| Java | `plugins/java/<name>/` | `<name>.jar` + `plugin.json` | Java 网关内独立 ClassLoader 反射调用 |

---

## 八、里程碑规划

| 阶段 | 内容 | 状态 |
|------|------|------|
| M1 基础壳 | Electron 热键/托盘/搜索框 UI | ✅ |
| M2 网关 | Javalin 路由 + SQLite 读写 | ✅ |
| M3 搜索链路 | 前端 ↔ 网关 ↔ SQLite | ✅ |
| M4 Python 运行时 | FastAPI 常驻 /execute | ✅ |
| M5 跨语言调用 | Java → Python 链路 | ✅ |
| M6 JS 插件加载器 | 本地 IPC 执行 + 热重载 | ✅ |
| M7 参数通道 | args_schema 表单 + 校验 | ✅ |
| M8 SQL 可视化 | 脚本执行表格展示 | ✅ 完善版（表格渲染+管理面板） |
| M9 Java 插件执行器 | jar 加载 + SPI 规范 + 热重载 | ✅ |
| M10 打包分发 | electron-builder + JRE + 便携 Python | ✅ 代码层完成（main.js 路径改造 + Python 自拉起 + build-runtime.ps1）；NSIS 安装包产出待图标到位后 `npm run dist` |

---

## 九、风险与对策

| 风险 | 等级 | 对策 |
|------|------|------|
| Java 插件沙箱隔离缺失（反射可触达全 JVM） | 高 | 已落地：默认 ClassLoader 隔离；可选 `sandbox: "process"` 启用独立 JVM 子进程 + 30s 超时保护；剩余更细粒度文件/网络限制为远期可选 |
| 多进程文件锁冲突 | 中 | 已解决：SQLite 单写者硬约束 |
| Python 依赖分发体积大 | 中 | 已评估：便携 Python（拷贝 `.venv`，保留可执行解释器以支持插件动态加载）+ jlink 精简 JRE 17；体积预估 30-80MB + 40-60MB；P3 实施（见 architecture.md 9.2-2/3） |
| 全局热键冲突 | 低 | 可配置 + 冲突检测提示 |
| 插件质量参差导致主进程崩溃（JS） | 中 | 已落地：主进程 try/catch 包裹执行并返回错误 toast，自动上报 `t_exec_log`（status=fail）；后续可选沙箱 webview/worker 执行 |

---

## 十、验收标准（MVP 回归清单）

- [x] 网关 `/health` 返回 `{"status":"ok","python":"up"}`
- [x] 搜索「文件统计」命中 Python 插件且返回 `argsSchema`
- [x] 必填参数表单弹出，填参后执行输出统计结果
- [x] 缺参调用被后端拦截（`缺少必填参数: 目录路径`）
- [x] 无参插件（时间戳）直接执行、不弹表单
- [x] 搜索「base64」→ 弹表单 → 填 `Hello FastBox` → 输出 `SGVsbG8gRmFzdEJveA==`
- [x] 搜索「sha256」→ 弹表单 → 填 `Hello FastBox` → 输出 `4133109adf964da660ff5177171834e75d035c68793d39f8fae97c28f52d7fa3`
- [x] 计算器 `1+2*3=7`；文件打开正常；SQL 查询返回表格
- [x] Java 插件 `sandbox: "process"` 模式下，异常/System.exit/死循环均不拖垮网关
- [x] 搜索「fail-test」→ 执行 → 面板提示 `JS 插件执行失败: fail-test 插件故意抛出的异常`，`t_exec_log` 有失败记录
- [x] 日志统一：`data/logs/{gateway,python,electron}.log`，级别 `FASTBOX_LOG_LEVEL` 可配，文件轮转不无限增长
- [x] 所有服务在线：Electron + Java 网关 + Python FastAPI
- [x] 打包与更新策略已评估：architecture.md 第九节决策记录 + `electron/electron-builder.yml` 骨架（extraResources 含 jre/python/gateway/plugins）
- [x] main.js 路径改造（`app.isPackaged` 分支 + `process.resourcesPath` 解析 GATEWAY_JAR/JRE/JS plugins）+ Python 自拉起 + 数据目录走 `app.getPath('userData')`
- [x] scripts/build-runtime.ps1 一站式生成 jlink JRE + portable Python
- [ ] 完整打包冒烟：图标（icon.ico）补充后 `npm run dist` 产出 NSIS 安装包（P3 收尾）

---

## 十一、附录

### 术语表

| 术语 | 含义 |
|------|------|
| 网关 | Java Javalin HTTP 服务（8764），核心调度枢纽 |
| 运行时 | Python FastAPI 常驻服务（8765） |
| args_schema | plugin.json 中声明插件参数的 JSON Schema |
| 单写者 | 仅 Java 网关直连 SQLite 的架构约束 |

### 变更记录

| 版本 | 日期 | 变更 |
|------|------|------|
| v0.1 | 2026-08-14 | 初稿：MVP 范围对齐，覆盖插件参数通道 |
| v0.2 | 2026-08-14 | 新增 JS 插件执行器、开发者体验、验收清单；同步架构文档第七节 |
| v0.3 | 2026-08-14 | 新增 Java 插件执行器（SPI + ClassLoader + 示例 sha256）；同步架构文档第八节；uiTest 增加 java 模式 |
| v0.4 | 2026-08-14 | 新增 Java 插件进程级沙箱隔离（`sandbox: "process"` + 30s 超时），更新 NFR/风险/验收清单 |
| v0.5 | 2026-08-14 | 新增执行留痕（`t_exec_log` 插件级记录 + `/api/exec-log` 上报 + `/api/exec-logs` 查询）；新增网关/Python/Electron 统一日志（轮转 + `FASTBOX_LOG_LEVEL` 级别）；插件数量上限保护；JS 异常回归插件 `fail-test` |
| v0.6 | 2026-08-14 | 新增打包与更新策略评估：electron-updater + generic 自建服务器决策、jlink JRE + 便携 Python 随包方案、`electron-builder.yml` 骨架落地；同步 architecture.md 第九节 |
| v0.7 | 2026-08-17 | 搜索历史联想 + 收藏管理 UI 完整落地：历史去重记录（100 条上限）、面板唤起展示历史、搜索结果星标收藏、收藏面板（Tab 切换+删除+重执行）、4 个新 API 端点；uiTest 新增 search/favorites 模式 |
| v0.8 | 2026-08-17 | #19 SQL 可视化完善（结构化表格渲染 + 复制 CSV + SQL 脚本管理面板 + 新建/编辑编辑器）+ #20 示例 SQL 脚本 5 个（exec-top10/search-stats/plugin-list/favorite-list/db-overview）+ SqlScriptScanner 启动扫描 scripts/sql/ 注册到 t_sql_script；#22 P3 完整打包代码层面落地：main.js `app.isPackaged` 双模式路径解析 + Python FastAPI 自拉起 + logger.js 数据目录走 userData + electron-builder 依赖 + scripts/build-runtime.ps1 一站式构建脚本；剩余图标 + 实际 NSIS 产出待 P3 收尾 |
