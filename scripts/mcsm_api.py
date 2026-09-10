r"""经 SSH 隧道调用 MCSM (MCSManager v10) 面板 / daemon API。

为什么要走隧道：MCSM 只监听本机（23333/24444，未对公网开放），
本地经 frps 的 SSH(10022) 建 direct-tcpip 通道即可安全调用，无需暴露面板端口。

⚠️ 三个「不读源码就一定会踩」的点（均已从 web/app.js 得到证实，别再猜）：

  1. **端口与直觉相反**（用进程路径锚定）：
       panel(web) = **23333**   ← ...\mcsmanager\web\node_app.exe
       daemon     = **24444**   ← ...\mcsmanager\daemon\node_app.exe
     佐证：`web/data/RemoteServiceConfig/*.json` 里写的是 `port: 24444`（面板连 daemon）。
     按 "web=24444" 调会一路 404，极易误判成"路由不对/密码错"。

  2. **必须带 `X-Requested-With: XMLHttpRequest`**，否则 403「无法找到请求头
     x-requested-with」（源码里的 `isAjax(ctx)`）。

  3. **面板鉴权＝会话 Cookie + `?token=` 查询参数**（不是 Authorization 头、
     也不是 X-Request-Key）。源码 permission 中间件：
        requestToken = ctx.query.token;  realToken = ctx.session.token;
        requestToken !== realToken → tokenError
        还要 ctx.session.login===true && session.uuid && session.userName
     所以 `POST /api/auth/login` 拿到的 token **必须**配合它下发的 Set-Cookie 一起用：
        Cookie: <Set-Cookie 值>   且   URL 上带 ?token=<token>
     （另有 API-Key 通道：头 `x-request-api-key` 或 ?apikey=，但要求用户 apiKey 已生成。）

凭据（**绝不入库**）：
  - 面板账号密码：环境变量 MCSM_USER / MCSM_PASS，或本地私密文件 ~/.mcsm_secret
    （两行：用户名、密码；仅本机可读）
  - daemon API Key：从 daemon 的 global.json 现读（不硬编码）

用法：
  # ---- 面板（23333）：登录 / 实例管理 ----
  python scripts/mcsm_api.py panel login            # 登录，把 {token, cookie} 缓存到 ~/.mcsm_token
  python scripts/mcsm_api.py panel get  /api/instance
  python scripts/mcsm_api.py panel post /api/instance '{"nickname":"..."}'

  # ---- daemon（24444）：底层实例操作 ----
  python scripts/mcsm_api.py get  /api/instance
  python scripts/mcsm_api.py post /api/instance '<json>'

  # ---- 路由未知时先探测 ----
  python scripts/mcsm_api.py panel probe
"""
from __future__ import annotations

import json
import os
import re
import sys

sys.path.insert(0, "scripts")
from mc_remote import connect, ps  # noqa: E402

MCSM_DIR = (r"C:\Users\Administrator\Downloads\mcsmanager_windows_release"
            r"\mcsmanager")
GLOBAL_JSON = MCSM_DIR + r"\daemon\data\Config\global.json"
PANEL_PORT = 23333
DAEMON_PORT = 24444

_SECRET = os.path.join(os.path.expanduser("~"), ".mcsm_secret")
_TOKEN = os.path.join(os.path.expanduser("~"), ".mcsm_token")


def creds() -> tuple[str, str]:
    user, pw = os.environ.get("MCSM_USER", ""), os.environ.get("MCSM_PASS", "")
    if (not user or not pw) and os.path.isfile(_SECRET):
        lines = [x.strip() for x in open(_SECRET, encoding="utf-8").read().splitlines()]
        if len(lines) >= 2:
            user, pw = lines[0], lines[1]
    return user, pw


def load_session() -> dict:
    if os.path.isfile(_TOKEN):
        try:
            return json.loads(open(_TOKEN, encoding="utf-8").read())
        except Exception:  # noqa: BLE001
            return {}
    return {}


def save_session(token: str, cookie: str) -> None:
    with open(_TOKEN, "w", encoding="utf-8") as f:
        json.dump({"token": token, "cookie": cookie}, f)
    try:
        os.chmod(_TOKEN, 0o600)
    except OSError:
        pass


def daemon_key(cli) -> str:
    """从 daemon 的 global.json 读取 API Key。"""
    txt = ps(cli, f"Get-Content '{GLOBAL_JSON}' -Encoding UTF8")
    m = re.search(r'"key"\s*:\s*"([^"]+)"', txt)
    return m.group(1) if m else ""


def _dechunk(raw: bytes) -> bytes:
    """Express 默认 Transfer-Encoding: chunked，必须解块后才能 json.loads。"""
    if b"\r\n" not in raw:
        return raw
    out, buf = b"", raw
    while True:
        nl = buf.find(b"\r\n")
        if nl < 0:
            break
        size_field = buf[:nl].split(b";")[0].strip()
        try:
            size = int(size_field, 16)
        except ValueError:
            return raw            # 不是 chunked，原样返回
        if size == 0:
            break
        start = nl + 2
        out += buf[start:start + size]
        buf = buf[start + size + 2:]
    return out or raw


def request(cli, method: str, path: str, body=None, *, port: int,
            headers: dict | None = None) -> tuple[str, str]:
    ch = cli.get_transport().open_channel(
        "direct-tcpip", ("127.0.0.1", port), ("127.0.0.1", 0), timeout=20)
    ch.settimeout(30)
    payload = b"" if body is None else json.dumps(body).encode("utf-8")
    hdrs = {"Host": f"127.0.0.1:{port}", "Content-Type": "application/json",
            "Connection": "close", "Accept": "application/json",
            # 见模块头第 2 点：缺这个头面板直接 403
            "X-Requested-With": "XMLHttpRequest"}
    hdrs.update(headers or {})
    head = (f"{method} {path} HTTP/1.1\r\n"
            + "".join(f"{k}: {v}\r\n" for k, v in hdrs.items())
            + f"Content-Length: {len(payload)}\r\n\r\n").encode("utf-8")
    ch.sendall(head + payload)
    data = b""
    while True:
        chunk = ch.recv(65536)
        if not chunk:
            break
        data += chunk
    ch.close()
    raw_head, _, raw_body = data.partition(b"\r\n\r\n")
    raw_body = _dechunk(raw_body)
    return raw_head.decode("utf-8", "replace"), raw_body.decode("utf-8", "replace")


def _cookie_from(head: str) -> str:
    """从响应头里拼出 Cookie（可能有多条 Set-Cookie）。"""
    parts = []
    for line in head.splitlines():
        if line.lower().startswith("set-cookie:"):
            kv = line.split(":", 1)[1].strip()
            parts.append(kv.split(";", 1)[0])
    return "; ".join(parts)


def _with_token(path: str, token: str) -> str:
    if not token:
        return path
    sep = "&" if "?" in path else "?"
    return f"{path}{sep}token={token}"


def panel_call(cli, method: str, path: str, body=None) -> tuple[str, str]:
    """带会话（Cookie + ?token=）的面板调用。"""
    s = load_session()
    tok = s.get("token", "")
    hdrs = {"Cookie": s["cookie"]} if s.get("cookie") else {}
    return request(cli, method, _with_token(path, tok), body,
                   port=PANEL_PORT, headers=hdrs)


def _show(h: str, b: str) -> None:
    print("== 状态 ==")
    print(h.splitlines()[0] if h else "(no status)")
    print("== 响应 ==")
    try:
        print(json.dumps(json.loads(b), ensure_ascii=False, indent=2)[:4000])
    except Exception:  # noqa: BLE001
        print(b[:2000])


def do_login(cli) -> int:
    user, pw = creds()
    if not user or not pw:
        print("[✗] 未取到面板账号：设 MCSM_USER/MCSM_PASS 或写 ~/.mcsm_secret")
        return 1
    h, b = request(cli, "POST", "/api/auth/login",
                   {"username": user, "password": pw}, port=PANEL_PORT)
    status = h.splitlines()[0] if h else ""
    if " 200" not in status:
        print(f"[✗] 登录失败：{status}")
        print(b[:400])
        return 1
    token, cookie = "", _cookie_from(h)
    try:
        r = json.loads(b)
        token = r.get("data") or r.get("token") or ""
        if isinstance(token, dict):
            token = token.get("token", "")
    except Exception:  # noqa: BLE001
        pass
    if not token:
        print("[✗] 响应里没有 token")
        _show(h, b)
        return 1
    save_session(token, cookie)
    print(f"[✓] 登录成功；token {token[:10]}… / cookie {'有' if cookie else '无'}"
          f" 已缓存到 {_TOKEN}")
    return 0


def main() -> int:
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 1

    use_panel = args[0] == "panel"
    if use_panel:
        args = args[1:]
    if not args:
        print(__doc__)
        return 1

    cli = connect()
    try:
        if args[0] == "probe":
            port = PANEL_PORT if use_panel else DAEMON_PORT
            if use_panel:
                s = load_session()
                hdrs = {"Cookie": s["cookie"]} if s.get("cookie") else None
                print(f"[*] 探测 127.0.0.1:{port}（panel，带会话）")
            else:
                hdrs = {"X-Request-Key": daemon_key(cli)}
                print(f"[*] 探测 127.0.0.1:{port}（daemon）")
            for p in ["/", "/api", "/api/auth", "/api/overview", "/api/instance",
                      "/api/instance/list", "/api/remote_services", "/api/users",
                      "/api/version", "/api/settings"]:
                try:
                    h, b = request(cli, "GET", p, None, port=port, headers=hdrs)
                    status = h.splitlines()[0] if h else "(no status)"
                    print(f"GET   {p:<26} {status:<26} {b.replace(chr(10), ' ')[:90]}")
                except Exception as e:  # noqa: BLE001
                    print(f"GET   {p:<26} ERR {e}")
            return 0

        if use_panel:
            if args[0] == "login":
                return do_login(cli)
            method, path = args[0].upper(), args[1]
            body = json.loads(args[2]) if len(args) > 2 else None
            h, b = panel_call(cli, method, path, body)
            _show(h, b)
            return 0

        method, path = args[0].upper(), args[1]
        body = json.loads(args[2]) if len(args) > 2 else None
        h, b = request(cli, method, path, body, port=DAEMON_PORT,
                       headers={"X-Request-Key": daemon_key(cli)})
        _show(h, b)
        return 0
    finally:
        cli.close()


if __name__ == "__main__":
    raise SystemExit(main())
