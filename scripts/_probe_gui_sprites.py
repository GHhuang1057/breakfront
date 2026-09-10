"""列出原版客户端 jar 里 GUI sprite 清单与九宫格元数据（为自研材质包定尺寸/切边）。

产物：
  scripts/_vanilla_gui_sprites.txt   条目全路径 + 字节数
  scripts/_vanilla_gui_mcmeta.txt    <path>.mcmeta 原文（九宫格 border / 缩放类型）
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, "scripts")
from mc_remote import connect, ps  # noqa: E402

JAR = r"C:\Users\Administrator\.gradle\caches\fabric-loom\1.21.1\minecraft-client.jar"
OUT_LIST = r"J:\bfbuild\_gui_sprites.txt"
OUT_META = r"J:\bfbuild\_gui_mcmeta.txt"

PS = r"""
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jar='__JAR__'
$z=[IO.Compression.ZipFile]::OpenRead($jar)
try {
  $pref='assets/minecraft/textures/gui/'
  $ent = $z.Entries | Where-Object { $_.FullName.StartsWith($pref) -and $_.FullName.EndsWith('.png') }
  ($ent | ForEach-Object { $_.FullName.Substring($pref.Length) + ' ' + $_.Length }) |
      Set-Content -Path '__OUT_LIST__' -Encoding UTF8
  Write-Output ('png entries = ' + $ent.Count)

  $sb = New-Object System.Text.StringBuilder
  foreach($e in ($z.Entries | Where-Object { $_.FullName.StartsWith($pref) -and $_.FullName.EndsWith('.png.mcmeta') })) {
    $rd = New-Object IO.StreamReader($e.Open())
    $txt = $rd.ReadToEnd(); $rd.Close()
    [void]$sb.AppendLine('### ' + $e.FullName)
    [void]$sb.AppendLine($txt.Trim())
  }
  [IO.File]::WriteAllText('__OUT_META__', $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
  Write-Output ('mcmeta entries = ' + ($z.Entries | Where-Object { $_.FullName.StartsWith($pref) -and $_.FullName.EndsWith('.png.mcmeta') }).Count)
} finally { $z.Dispose() }
"""


def main() -> int:
    cli = connect()
    try:
        out = ps(cli, PS.replace("__JAR__", JAR)
                           .replace("__OUT_LIST__", OUT_LIST)
                           .replace("__OUT_META__", OUT_META))
        print(out.strip())
        sftp = cli.open_sftp()
        for remote, local in ((OUT_LIST, "scripts/_vanilla_gui_sprites.txt"),
                              (OUT_META, "scripts/_vanilla_gui_mcmeta.txt")):
            sftp.get(remote, local)
            print(f"[✓] {os.path.basename(local)}: {os.path.getsize(local)} 字节")
        sftp.close()
    finally:
        cli.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
