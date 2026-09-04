#!/usr/bin/env python3
"""生成第三方模组许可清单（公开发包前合规自查用）。

从发布产物 zip 或本地 mods 目录读取 jar 内 fabric.mod.json 的
name/version/license/authors/contact 字段，输出 Markdown 表 + 提示。

用法:
  python licenses.py --dir <mods目录>            # 扫描目录
  python licenses.py --zip <dev client zip>      # 扫描发布包内 mods/
  python licenses.py --server-zip <dev server zip>
输出: 打印 Markdown（可重定向到 docs/third-party-licenses.md）
"""
import argparse
import io
import json
import zipfile
from pathlib import Path

KEYS = ("name", "version", "license", "authors", "contact")


def read_meta(data: bytes):
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            names = z.namelist()
            cand = [n for n in names if n.endswith("fabric.mod.json")]
            # 优先 mod 根命名空间（同包可能多个；取第一个）
            for n in cand:
                raw = z.read(n)
                return json.loads(raw.decode("utf-8", "ignore"))
    except Exception:
        return None
    return None


def fmt(v):
    if v is None:
        return "-"
    if isinstance(v, (list, tuple)):
        return ", ".join(str(x) for x in v)
    if isinstance(v, dict):
        return ", ".join(f"{k}: {x}" for k, x in v.items())
    return str(v)


def gather(files):
    rows = []
    for p in sorted(files, key=lambda x: x.name.lower()):
        if p.name.startswith("breakfront"):
            continue  # 自研模组不进第三方清单
        meta = read_meta(p.read_bytes())
        if not meta or not meta.get("id"):
            rows.append((p.name, "?", "无法解析 fabric.mod.json", "-", "-"))
            continue
        rows.append((p.name,
                     meta.get("version", "?"),
                     meta.get("license", "?"),
                     fmt(meta.get("authors")),
                     fmt(meta.get("contact"))))
    return rows


def render(rows, source):
    lines = [f"# BREAKFRONT 第三方模组许可清单（来源: {source}）\n",
             "> 自动生成：pack/tools/licenses.py · 发布前请人工核对各项目页最新许可\n",
             "",
             "| 文件 | 版本 | License | Authors | 主页/联系 |",
             "|---|---|---|---|---|"]
    for name, ver, lic, auth, con in rows:
        lic = lic.replace("\n", " / ")[:60]
        auth = auth[:80]
        con = con[:120]
        lines.append(f"| {name} | {ver} | {lic} | {auth} | {con} |")
    lines += ["",
              "## 注意事项",
              "- 第三方模组仅作「内容源」集成，其许可由各自作者所有；发布整合包前请逐项确认（尤其 TaCZ 枪械/枪包与地图素材）。",
              "- 自研 breakfront / breakfront-client 以 MIT 发布，不属于上表。"]
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir")
    ap.add_argument("--zip")
    ap.add_argument("--server-zip")
    a = ap.parse_args()
    files = []
    source = ""
    if a.dir:
        source = a.dir
        files = list(Path(a.dir).glob("*.jar"))
    elif a.zip or a.server_zip:
        zp = a.zip or a.server_zip
        source = zp
        with zipfile.ZipFile(zp) as z:
            for n in z.namelist():
                if n.startswith("mods/") and n.endswith(".jar"):
                    files.append(Path(f"!{n}"))
        # 需要保留字节：改为内存收集
        tmp = []
        with zipfile.ZipFile(zp) as z:
            for n in z.namelist():
                if n.startswith("mods/") and n.endswith(".jar"):
                    tmp.append((Path(n.split("/", 1)[1]), z.read(n)))
        rows = []
        for name, data in sorted(tmp, key=lambda t: t[0].name.lower()):
            if name.name.startswith("breakfront"):
                continue
            meta = read_meta(data)
            if not meta or not meta.get("id"):
                rows.append((name.name, "?", "无法解析", "-", "-"))
                continue
            rows.append((name.name, meta.get("version", "?"),
                         meta.get("license", "?"), fmt(meta.get("authors")),
                         fmt(meta.get("contact"))))
        print(render(rows, source))
        return
    if not files:
        ap.error("need --dir or --zip")
    print(render(gather(files), source))


if __name__ == "__main__":
    main()
