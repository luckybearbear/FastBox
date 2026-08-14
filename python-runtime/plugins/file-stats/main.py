"""文件统计插件 — 统计目录内文件数量、总大小、扩展名分布

入参（args）: [目录路径]
返回: {"detail": "...", "summary": {...}}
"""
from collections import Counter
from pathlib import Path


def run(keyword: str, args: list, user_config: dict):
    if not args:
        return {"detail": "请提供目录路径，例如 args: [\"D:/test\"]"}

    target = Path(args[0])
    if not target.exists():
        return {"detail": f"目录不存在: {target}"}
    if not target.is_dir():
        return {"detail": f"不是目录: {target}"}

    file_count = 0
    total_size = 0
    ext_counter = Counter()
    max_depth = int(user_config.get("max_depth", 5))

    for p in target.rglob("*"):
        try:
            if p.is_file():
                file_count += 1
                size = p.stat().st_size
                total_size += size
                ext_counter[p.suffix.lower() or "(无扩展名)"] += 1
        except (PermissionError, OSError):
            pass
        if file_count > 50000:  # 安全上限
            break

    top_ext = ext_counter.most_common(10)

    lines = [
        f"目录: {target}",
        f"文件数: {file_count}",
        f"总大小: {_fmt_size(total_size)}",
        f"平均大小: {_fmt_size(total_size // max(1, file_count))}",
        "",
        "扩展名 TOP10:",
    ]
    for ext, cnt in top_ext:
        lines.append(f"  {ext:<12} {cnt:>6} 个")
    lines.append("")
    lines.append(f"（最大扫描深度 {max_depth} 层，超 5 万文件自动截断）")

    return {
        "detail": "\n".join(lines),
        "summary": {
            "dir": str(target),
            "file_count": file_count,
            "total_size": total_size,
            "top_extensions": dict(top_ext),
        },
    }


def _fmt_size(n: int) -> str:
    for unit in ["B", "KB", "MB", "GB", "TB"]:
        if n < 1024 or unit == "TB":
            return f"{n:.1f} {unit}" if unit != "B" else f"{n} {unit}"
        n /= 1024
    return f"{n} TB"
