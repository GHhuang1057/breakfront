#!/usr/bin/env python3
"""静态校验 index.html：提取 <script> 内联脚本做 node --check 语法验证 + 引用完整性检查。"""
import pathlib, re, subprocess, sys, tempfile, os

HTML = pathlib.Path(r"G:/BF_MC/core/src/main/resources/assets/bfadmin/index.html")
src = HTML.read_text(encoding="utf-8")

# 1) 提取内联脚本（排除带 src 的外链）
scripts = []
for m in re.finditer(r"<script(?![^>]*\bsrc=)[^>]*>(.*?)</script>", src, re.S):
    scripts.append(m.group(1))
js = "\n;\n".join(scripts)
print(f"[*] 内联脚本块 {len(scripts)} 段，合计 {len(js)} 字符")

# 2) node --check 语法校验（写成 .js 文件，包一层以免顶层 return 之类误报）
node = r"C:/Users/huang/.workbuddy/binaries/node/versions/22.22.2-2/node.exe"
tmp = pathlib.Path(tempfile.gettempdir()) / "bfadmin_check.js"
tmp.write_text(js, encoding="utf-8")
r = subprocess.run([node, "--check", str(tmp)], capture_output=True, text=True)
if r.returncode != 0:
    print("[FAIL] JS 语法错误：")
    print(r.stderr[:3000])
    sys.exit(1)
print("[✓] JS 语法检查通过")

# 3) 引用完整性：onclick/onchange 等内联处理器中调用的函数必须已定义
handlers = set(re.findall(r"on(?:click|change|input|dblclick)\s*=\s*\"([^\"]+)\"", src))
called = set()
for h in handlers:
    for fn in re.findall(r"([A-Za-z_$][\w$]*)\s*\(", h):
        called.add(fn)
defined = set(re.findall(r"function\s+([A-Za-z_$][\w$]*)\s*\(", js))
defined |= set(re.findall(r"(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:function|\(|async)", js))
builtin = {"if","for","while","return","typeof","parseFloat","parseInt","String","Number","JSON"}
missing = sorted(c for c in called if c not in defined and c not in builtin)
if missing:
    print("[FAIL] 内联处理器引用了未定义的函数：" + ", ".join(missing))
    sys.exit(1)
print("[✓] 内联处理器引用完整")

# 4) 检查已删除的旧 Canvas API 是否仍被引用
dead = ["setupCanvas", "resizeCanvas", "drawMap", "fitView", "findSelById", "mapState.cam",
        "mapcanvas", "gridsel", "mapbox", "updateZoneFromDrag"]
found = [d for d in dead if d in src]
if found:
    print("[FAIL] 仍引用已删除的旧实现符号：" + ", ".join(found))
    sys.exit(1)
print("[✓] 无旧 Canvas 符号残留")

# 5) Leaflet 关键 API 使用核对
for api in ["L.map", "L.CRS.Simple", "L.rectangle", "L.marker", "L.divIcon", "L.imageOverlay",
            "L.layerGroup", "createPane", "fitBounds", "invalidateSize"]:
    if api not in js:
        print(f"[!] 警告：未使用 Leaflet API {api}")
print("[✓] Leaflet API 使用检查完成")

print("\n全部前端静态检查通过")
