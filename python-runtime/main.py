"""
FastBox Python 常驻服务（FastAPI）
==================================
替代 stdio 进程池方案：Python 解释器常驻内存，Java 网关通过 HTTP 调用。

协议契约：
    GET  /health                    -> {"status": "ok"}
    POST /execute                   -> {"code": 0, "data": {...}, "message": "ok"}
         body: {
             "keyword": str,        # 触发关键词
             "args": [...],         # 脚本参数
             "userConfig": {...}    # 用户配置
         }
    GET  /plugins                   -> {"plugins": [...]}

插件规范：
    python-runtime/plugins/<name>/
        main.py      # 入口，必须实现 run(keyword, args, user_config) -> dict
        plugin.json  # {name, description, keywords: ["xx","yy"], args_schema: []}

返回规范：
    code = 0    成功
    code = 1    脚本内部错误（message 为错误信息）
    code = -1   服务/参数错误

运行：
    python main.py            # 默认 127.0.0.1:8765
    python main.py --port 9000
"""

import json
import logging
import os
import sys
import time
import importlib.util
from logging.handlers import RotatingFileHandler
from pathlib import Path
from typing import Any, Dict, List, Optional

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
import uvicorn

BASE_DIR = Path(__file__).resolve().parent
PLUGIN_DIR = BASE_DIR / "plugins"

# ---------- 日志统一配置：data/logs/python.log（与网关 / Electron 同目录） ----------
LOG_DIR = Path(os.environ.get("FASTBOX_DATA_DIR", BASE_DIR.parent / "data")) / "logs"
LOG_LEVEL = getattr(logging, os.environ.get("FASTBOX_LOG_LEVEL", "INFO").upper(), logging.INFO)
LOG_DIR.mkdir(parents=True, exist_ok=True)


def _setup_logging() -> None:
    """root + uvicorn 日志写入文件（10MB 轮转保留 7 份）+ 控制台"""
    fmt = logging.Formatter("%(asctime)s [%(levelname)s] %(name)s - %(message)s")
    file_handler = RotatingFileHandler(
        LOG_DIR / "python.log", maxBytes=10 * 1024 * 1024, backupCount=7, encoding="utf-8"
    )
    file_handler.setFormatter(fmt)
    console = logging.StreamHandler()
    console.setFormatter(fmt)

    root = logging.getLogger()
    root.setLevel(LOG_LEVEL)
    root.handlers = [file_handler, console]

    # uvicorn 自身 logger 挂到同一文件（access 日志降噪为 WARNING，避免每请求刷屏）
    for name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        lg = logging.getLogger(name)
        lg.handlers = [file_handler, console]
        lg.propagate = False
        if name == "uvicorn.access":
            lg.setLevel(logging.WARNING)


_setup_logging()
logger = logging.getLogger("fastbox.python")

app = FastAPI(title="FastBox Python Runtime", version="0.1.0")


class ExecuteRequest(BaseModel):
    keyword: str = ""
    args: List[Any] = []
    userConfig: Dict[str, Any] = {}


def _load_plugin(plugin_name: str) -> Optional[dict]:
    """加载插件模块，返回 {module, meta} 或 None"""
    pdir = PLUGIN_DIR / plugin_name
    meta_file = pdir / "plugin.json"
    main_file = pdir / "main.py"
    if not (pdir.is_dir() and main_file.is_file() and meta_file.is_file()):
        return None

    try:
        meta = json.loads(meta_file.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        meta = {"name": plugin_name, "description": "plugin.json 解析失败"}

    spec = importlib.util.spec_from_file_location(f"fb_plugin_{plugin_name}", main_file)
    module = importlib.util.module_from_spec(spec)
    try:
        spec.loader.exec_module(module)
    except Exception as e:
        return {"error": f"插件加载失败: {e}"}

    return {"module": module, "meta": meta}


@app.get("/health")
def health():
    return {"status": "ok", "version": "0.1.0", "plugins": len(list(PLUGIN_DIR.glob("*/main.py")))}


@app.get("/plugins")
def list_plugins():
    result = []
    for pdir in sorted(PLUGIN_DIR.iterdir()):
        if not pdir.is_dir():
            continue
        meta_file = pdir / "plugin.json"
        if not meta_file.is_file():
            continue
        try:
            meta = json.loads(meta_file.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            meta = {"name": pdir.name, "description": "解析失败"}
        result.append({"name": pdir.name, "keywords": meta.get("keywords", []), **meta})
    return {"plugins": result}


@app.post("/execute")
def execute(req: ExecuteRequest):
    start = time.time()
    # 参数兼容：args 可传 [plugin_name] 或 [plugin_name, arg1, ...]
    args = req.args if isinstance(req.args, list) else [req.args]
    if not args:
        return {"code": -1, "message": "缺少插件名参数", "cost_ms": 0}
    plugin_name = str(args[0])

    plugin = _load_plugin(plugin_name)
    if plugin is None:
        return {"code": -1, "message": f"插件不存在: {plugin_name}", "cost_ms": 0}
    if "error" in plugin:
        return {"code": 1, "message": plugin["error"], "cost_ms": 0}
    if not hasattr(plugin["module"], "run"):
        return {"code": 1, "message": f"插件 {plugin_name} 缺少 run() 函数", "cost_ms": 0}

    try:
        result = plugin["module"].run(
            keyword=req.keyword,
            args=args[1:],
            user_config=req.userConfig,
        )
        if not isinstance(result, dict):
            result = {"data": result}
        return {"code": 0, "data": result, "message": "ok", "cost_ms": int((time.time() - start) * 1000)}
    except Exception as e:
        logger.exception("插件 %s 执行异常", plugin_name)
        return {"code": 1, "message": f"{type(e).__name__}: {e}", "cost_ms": int((time.time() - start) * 1000)}


if __name__ == "__main__":
    port = 8765
    if "--port" in sys.argv:
        idx = sys.argv.index("--port")
        port = int(sys.argv[idx + 1])
    print(f"[FastBox Python Runtime] listening on http://127.0.0.1:{port}")
    print(f"[FastBox Python Runtime] plugin dir: {PLUGIN_DIR}")
    print(f"[FastBox Python Runtime] log file: {LOG_DIR / 'python.log'} (level={logging.getLevelName(LOG_LEVEL)})")
    # 日志级别统一由上方 logging 配置控制（log_config=None 禁用 uvicorn 内部配置覆盖）
    uvicorn.run(app, host="127.0.0.1", port=port, log_config=None)
