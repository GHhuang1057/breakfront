"""经 SSH 隧道调用 MCSM (MCSManager v10) daemon API。

为什么要走隧道：daemon 监听 127.0.0.1:23333（未对公网开放），
本地经 frps 的 SSH(10022) 建 direct-tcpip 通道即可安全调用，无需暴露面板 API 端口。

用法：
  python scripts/mcsm_api.py get  /api/overview
  python scripts/mcsm_api.py get  /api/instance
  python scripts/mcsm_api.py post /api/instance '<json>'
"""
from __future__ import annotations

import json
import re
import sys

sys.path.insert(0, "scripts")
from mc_remote import connect, ps  # noqa: E402

GLOBAL_JSON = (r"C:\Users\Administrator\Downloads\mcsmanager_windows_release"
               r"\mcsmanager\daemon\data\Config\global.json")
MCSM_PORT = 23333


def api_key(cli) -> str:
    """从 daemon 的 global.json 读取 API Key（不硬编码、不入库）。"""
    txt = ps(cli, f"Get-Content '{GLOBAL_JSON}' -Encoding UTF8")
    m = re.search(r'"key"\s*:\s*"([^"]+)"', txt)
    return m.group(1) if m else ""


def api(cli, method: str, path: str, body: object = None) -> tuple[str, str]:
    key = api_key(cli)
    ch = cli.get_transport().open_channel(
        "direct-tcpip", ("127.0.0.1", MCSM_PORT), ("127.0.0.1", 0), timeout=20)
    ch.settimeout(25)
    payload = b"" if body is None else json.dumps(body).encode("utf-8")
    head = (f"{method} {path} HTTP/1.1\r\n"
            f"Host: 127.0.0.1:{MCSM_PORT}\r\n"
            f"X-Request-Key: {key}\r\n"
            f"Content-Type: application/json\r\n"
            f"Content-Length: {len(payload)}\r\n"
            f"Connection: close\r\n\r\n").encode("utf-8")
    ch.sendall(head + payload)
    data = b""
    while True:
        chunk = ch.recv(65536)
        if not chunk:
            break
        data += chunk
    ch.close()
    raw_head, _, raw_body = data.partition(b"\r\n\r\n")
    return raw_head.decode("utf-8", "replace"), raw_body.decode("utf-8", "replace")


def main() -> int:
    args = sys.argv[1:]
    if len(args) < 2:
        print(__doc__)
        return 1
    method, path = args[0].upper(), args[1]
    body = json.loads(args[2]) if len(args) > 2 else None
    cli = connect()
    try:
        h, b = api(cli, method, path, body)
        print("== HEAD ==")
        print(h.splitlines()[0] if h else "(no status)")
        print("== BODY ==")
        try:
            print(json.dumps(json.loads(b), ensure_ascii=False, indent=2)[:3000])
        except Exception:  # noqa: BLE001
            print(b[:1500])
    finally:
        cli.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
