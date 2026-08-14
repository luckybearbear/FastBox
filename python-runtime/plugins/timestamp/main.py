"""时间戳转换插件 — Unix 时间戳与日期互转

入参（args）: [时间戳或日期字符串，可省略]
无参时返回当前时间戳
"""
import time
from datetime import datetime


def run(keyword: str, args: list, user_config: dict):
    now = datetime.now()
    lines = [f"当前时间: {now.strftime('%Y-%m-%d %H:%M:%S')}", f"当前时间戳: {int(time.time())}", ""]

    if args and args[0]:
        raw = str(args[0]).strip()
        # 纯数字 → 时间戳转日期
        if raw.isdigit():
            ts = int(raw)
            if len(raw) == 10:
                dt = datetime.fromtimestamp(ts)
            elif len(raw) == 13:
                dt = datetime.fromtimestamp(ts / 1000)
            else:
                return {"detail": "时间戳位数需为 10 位(秒)或 13 位(毫秒)"}
            lines.append(f"时间戳 {raw}")
            lines.append(f"  → {dt.strftime('%Y-%m-%d %H:%M:%S')}")
        else:
            # 日期字符串 → 时间戳
            for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d", "%Y/%m/%d %H:%M:%S", "%Y/%m/%d"):
                try:
                    dt = datetime.strptime(raw, fmt)
                    lines.append(f"日期 {raw}")
                    lines.append(f"  → 秒: {int(dt.timestamp())}")
                    lines.append(f"  → 毫秒: {int(dt.timestamp() * 1000)}")
                    break
                except ValueError:
                    continue
            else:
                lines.append(f"无法解析: {raw}（支持 YYYY-MM-DD[ HH:MM:SS]）")

    return {"detail": "\n".join(lines)}
