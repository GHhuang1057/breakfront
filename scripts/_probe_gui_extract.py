"""从原版客户端 jar 抽取 GUI 参考贴图到本地，并用 PIL 打印真实像素尺寸。

为什么需要：新 GUI sprite 体系里 `.mcmeta` 的 width/height 是**渲染目标尺寸**，
而源 PNG 自身尺寸决定细节不拉伸的区域。自研贴图要与之逐一对齐，必须知道真实像素。
"""
from __future__ import annotations

import base64
import io
import os
import sys

sys.path.insert(0, "scripts")
from mc_remote import connect, ps  # noqa: E402

JAR = r"C:\Users\Administrator\.gradle\caches\fabric-loom\1.21.1\minecraft-client.jar"
DUMP = r"J:\bfbuild\_gui_dump.txt"
LOCAL_DUMP = "scripts/_gui_dump.txt"
DEST = "scripts/_vanilla_png"

# 关心的相对路径前缀（相对 assets/minecraft/textures/gui/）
WANT = [
    "sprites/widget/",
    "sprites/popup/",
    "menu_background.png",
    "menu_list_background.png",
    "inworld_menu_background.png",
    "inworld_menu_list_background.png",
    "header_separator.png",
    "inworld_header_separator.png",
    "inworld_footer_separator.png",
    "tab_header_background.png",
    "title/background/panorama_overlay.png",
]

PS = r"""
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$pref='assets/minecraft/textures/gui/'
$want=@({want})
$lines=New-Object System.Collections.Generic.List[string]
$z=[IO.Compression.ZipFile]::OpenRead('{jar}')
try {{
  foreach($e in $z.Entries){{
    if(-not $e.FullName.StartsWith($pref)){{ continue }}
    $rel=$e.FullName.Substring($pref.Length)
    $hit=$false
    foreach($w in $want){{ if($rel.StartsWith($w)){{ $hit=$true; break }} }}
    if(-not $hit){{ continue }}
    $ms=New-Object IO.MemoryStream
    $s=$e.Open(); $s.CopyTo($ms); $s.Close()
    $lines.Add($rel + '|' + [Convert]::ToBase64String($ms.ToArray()))
  }}
}} finally {{ $z.Dispose() }}
[IO.File]::WriteAllLines('{dump}', $lines)
Write-Output ('dumped ' + $lines.Count)
"""


def main() -> int:
    want = ",".join("'" + w + "'" for w in WANT)
    cli = connect()
    try:
        print(ps(cli, PS.format(want=want, jar=JAR, dump=DUMP)).strip())
        sftp = cli.open_sftp()
        sftp.get(DUMP, LOCAL_DUMP)
        sftp.close()
    finally:
        cli.close()

    from PIL import Image
    os.makedirs(DEST, exist_ok=True)
    rows = []
    with open(LOCAL_DUMP, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\r\n")
            if "|" not in line:
                continue
            rel, b64 = line.split("|", 1)
            data = base64.b64decode(b64)
            path = os.path.join(DEST, rel)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "wb") as g:
                g.write(data)
            try:
                im = Image.open(io.BytesIO(data))
                rows.append((rel, f"{im.width}x{im.height}", im.mode, len(data)))
            except Exception as e:  # noqa: BLE001
                rows.append((rel, "?", "?", str(e)))
    for r in sorted(rows):
        print(f"{r[0]:<56} {r[1]:>9}  {r[2]:<7} {r[3]}")
    print(f"\n共 {len(rows)} 个参照贴图 → {DEST}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
