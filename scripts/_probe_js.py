"""把服务器上 squaremap 前端的 World.js 取回本地，用于确认坐标换算公式。"""
import base64
import sys

sys.path.insert(0, "scripts")
from mc_remote import connect, ps  # noqa: E402

FILES = [
    r"J:\bfserver\server\squaremap\web\js\modules\util\World.js",
    r"J:\bfserver\server\squaremap\web\js\modules\Squaremap.js",
]

cli = connect()
try:
    for f in FILES:
        out = ps(cli, f"$b=[IO.File]::ReadAllBytes('{f}'); "
                      f"[Convert]::ToBase64String($b)")
        b64 = "".join(out.split())
        try:
            txt = base64.b64decode(b64).decode("utf-8", "replace")
        except Exception as e:  # noqa: BLE001
            print(f"!! {f}: {e}")
            continue
        name = f.rsplit("\\", 1)[-1]
        with open("scripts/_sqm_" + name, "w", encoding="utf-8") as fh:
            fh.write(txt)
        print(f"[✓] {name}: {len(txt)} chars -> scripts/_sqm_{name}")
finally:
    cli.close()
