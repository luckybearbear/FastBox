# FastBox — 个人自用跨平台快捷工具

跨平台桌面快捷启动工具（UTools 风格），核心差异：**打通 JS / Java / Python / SQL 四类扩展**。

## 技术栈

| 层 | 技术 | 说明 |
|----|------|------|
| UI 交互层 | Electron | 全局热键 + 悬浮搜索面板 + 托盘 |
| 网关调度层 | Java 17 + Javalin | 轻量 HTTP 网关（替代 SpringBoot），核心枢纽 |
| 脚本执行层 | Python 3.13 + FastAPI | 常驻服务（替代 stdio 进程池），统一 /execute 端点 |
| 数据持久层 | SQLite (JDBC) | 仅 Java 网关直连，Electron 一律走 HTTP |
| 通信协议 | HTTP | Electron ↔ Java ↔ Python 全链路 HTTP |

## 目录结构

```
FastBox/
├── electron/          # UI 层前端代码
│   └── src/
│       ├── main/      # 主进程（热键、托盘、窗口）
│       ├── preload/   # 安全桥接
│       └── renderer/  # 搜索面板 UI
├── java-gateway/      # Java 网关（Javalin + SQLite）
│   └── src/main/java/com/fastbox/gateway/
│       ├── server/    # HTTP 端点
│       ├── db/        # SQLite 操作
│       ├── plugin/    # 插件注册中心
│       └── python/    # Python 服务客户端
├── python-runtime/    # Python FastAPI 常驻服务
│   ├── main.py        # FastAPI 入口
│   └── plugins/       # Python 示例插件
├── plugins/           # 全部插件目录
│   ├── js/            # JS 插件（借鉴 UTools 规范）
│   ├── java/          # Java 插件（jar）
│   └── python/        # Python 插件（main.py + plugin.json）
├── data/              # 持久化数据
│   ├── local.db       # SQLite 主库（仅 Java 网关访问）
│   ├── cache/         # 脚本输出、图标缓存
│   └── logs/          # 滚动日志
├── scripts/sql/       # 用户自定义 SQL 脚本
└── docs/              # 架构文档
```

## 架构硬约束

1. **SQLite 单写者**：仅 Java 网关直连 `data/local.db`，Electron/Python 一律走 HTTP，避免 SQLite 文件锁冲突。
2. **Python 常驻**：Python 以 FastAPI 服务形式常驻，Java 网关通过 HTTP 调用，不做 stdio 进程池。
3. **统一 JSON 契约**：脚本/服务入参出参均为标准 JSON，stderr 单独捕获为错误日志。

## 快速启动（开发模式）

```bash
# 1. Python 常驻服务（端口 8765）
cd python-runtime
pip install -r requirements.txt
python main.py

# 2. Java 网关（端口 8764）
cd java-gateway
mvn package
java -jar target/fastbox-gateway.jar

# 3. Electron UI
cd electron
npm install
npm start
```

## MVP 路线（已调整版）

1. Electron 基础壳（热键 + 托盘 + 搜索框 UI）✅ 当前
2. Java 网关：HTTP 路由 + SQLite 读写
3. 前端调通网关：搜索 → SQLite 检索 → 返回结果
4. Python FastAPI 常驻服务 /execute 端点
5. 打通 Java → Python 调用链
6. JS 插件加载器（借鉴 UTools 规范，非完全兼容）
7. SQL 可视化执行界面
8. 打包（内置 JRE + 便携 Python）
