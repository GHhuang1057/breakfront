# -*- coding: utf-8 -*-
"""验证 BFDeploy 结果：mods 里 core jar 的 sha 是否等于 CI 清单里的。"""
import json
import sys
import urllib.request

sys.path.insert(0, "scripts")
from mc_remote import connect, ps  # noqa: E402

op = urllib.request.build_opener(urllib.request.ProxyHandler({}))
op.addheaders = [("User-Agent", "Mozilla/5.0 Chrome/126")]
m = json.load(op.open("https://bfupdate.geekhonize.top/breakfront/manifest.json", timeout=20))
want = {f["name"]: f["sha256"] for f in m["files"]}
print("manifest tag:", m["tag"])

cli = connect()
try:
    out = ps(cli, r"""
$mods = 'J:\bfserver\server\mods'
Get-ChildItem $mods -Filter 'breakfront*' | ForEach-Object {
  (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower() + '  ' + $_.Name
}
""")
    print("--- remote breakfront jars ---")
    print(out.strip())
    ok = True
    for name, sha in want.items():
        hit = sha in out
        print(f"{name}: {'MATCH' if hit else 'MISMATCH'}")
        ok = ok and hit
    # 服务端日志确认 Done + 无崩溃
    log = ps(cli, "(Get-Content 'J:\\bfserver\\server\\logs\\latest.log' -Encoding UTF8 -Tail 3) -join [char]10")
    print("--- server log tail ---")
    print(log.strip())
    print("RESULT:", "ALL MATCH" if ok else "MISMATCH FOUND")
finally:
    cli.close()
