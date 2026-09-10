"""MC 主机远程运维工具（2026-09-10）。

能力：
  exec <ps命令...>      经 SSH(10022 隧道) 在 MC 主机执行 PowerShell 命令
  log [n]               读服务端日志尾部 n 行（默认 40）
  rcon <minecraft指令>   经 SSH direct-tcpip 隧道连 25575 执行 RCON 指令

用法示例：
  python scripts/mc_remote.py log 60
  python scripts/mc_remote.py rcon "bf bot status"
  python scripts/mc_remote.py exec "Get-Process java"

依赖：paramiko（C:/Users/huang/.workbuddy/binaries/python/envs/default）
"""
from __future__ import annotations

import os
import re
import struct
import sys
import time

import paramiko

HOST = os.environ.get("BF_MC_HOST", "8.141.114.60")
PORT = int(os.environ.get("BF_MC_PORT", "10022"))
USER = os.environ.get("BF_MC_USER", "Administrator")

# ⚠️ 密码绝不入库（仓库是公开的）。来源优先级：
#   1) 环境变量 BF_MC_PW
#   2) 本地私密文件 ~/.bf_mc_secret（单行，gitignore 之外，仅本机可读）
_SECRET_FILE = os.path.join(os.path.expanduser("~"), ".bf_mc_secret")
PWD = os.environ.get("BF_MC_PW", "")
if not PWD and os.path.isfile(_SECRET_FILE):
    with open(_SECRET_FILE, encoding="utf-8") as _f:
        PWD = _f.read().strip()
RUN_DIR = r"J:\bfserver\server"
LOG_PATH = RUN_DIR + r"\logs\latest.log"
PROPS = RUN_DIR + r"\server.properties"
RCON_HOST = "127.0.0.1"
RCON_PORT = 25575


def connect(retries: int = 5) -> paramiko.SSHClient:
    """建立 SSH 连接；实测该链路会偶发 10054（远程强制断开，疑似连接频控），故自动重试。"""
    last: Exception | None = None
    for i in range(retries):
        cli = paramiko.SSHClient()
        cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        try:
            cli.connect(HOST, port=PORT, username=USER, password=PWD,
                        timeout=25, banner_timeout=25, auth_timeout=25,
                        allow_agent=False, look_for_keys=False)
            return cli
        except Exception as e:  # noqa: BLE001
            last = e
            try:
                cli.close()
            except Exception:  # noqa: BLE001
                pass
            if i < retries - 1:
                time.sleep(4 * (i + 1))
    raise last if last else RuntimeError("SSH 连接失败")


def ps(cli: paramiko.SSHClient, cmd: str) -> str:
    """执行 PowerShell 命令，返回 stdout（UTF-8 解码）。"""
    _in, out, err = cli.exec_command(cmd, timeout=290)
    data = out.read()
    err.read()
    # 远程 PowerShell 输出编码不定，UTF-8 优先，失败回退 GBK
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return data.decode("gbk", "replace")


# ---------------- RCON ----------------

def _rcon_packet(req_id: int, typ: int, body: str) -> bytes:
    payload = struct.pack("<ii", req_id, typ) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(payload)) + payload


def _read_packet(sock) -> tuple[int, int, str]:
    raw = _recv_exact(sock, 4)
    (length,) = struct.unpack("<i", raw)
    data = _recv_exact(sock, length)
    req_id, typ = struct.unpack("<ii", data[:8])
    body = data[8:-2].decode("utf-8", "replace")
    return req_id, typ, body


def _recv_exact(sock, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("RCON 连接被关闭")
        buf += chunk
    return buf


def rcon_password(cli: paramiko.SSHClient) -> str:
    """读 rcon.password 并按 Java Properties 规则反转义。

    实测该服务器的值被误写成整串提示文案
    （`RCON 127.0.0.1\\:25575 password\\=GoQDpr2Jj1aBRSYx`），
    其中 `\\:` `\\=` 是 Properties 的转义，Java 读取时会还原 —— 所以必须同样处理，
    否则认证必然失败。
    """
    text = ps(cli, f"Get-Content '{PROPS}' -Encoding UTF8 | Select-String '^rcon.password'")
    m = re.search(r"rcon\.password=(.*)", text)
    if not m:
        return ""
    raw = m.group(1).strip()
    # Properties 反转义：\: -> :   \= -> =   \\ -> \
    return re.sub(r"\\([:=\\])", r"\1", raw)


def run_rcon(cli: paramiko.SSHClient, command: str) -> str:
    """经 SSH 隧道连 RCON 并执行指令。"""
    pwd = rcon_password(cli)
    if not pwd:
        return "[rcon] 未取到 rcon.password（或未启用 RCON）"
    ch = cli.get_transport().open_channel(
        "direct-tcpip", (RCON_HOST, RCON_PORT), ("127.0.0.1", 0), timeout=20)
    ch.settimeout(20)
    try:
        ch.sendall(_rcon_packet(1, 3, pwd))          # AUTH
        req_id, _typ, body = _read_packet(ch)
        if req_id == -1:
            return "[rcon] 认证失败"
        ch.sendall(_rcon_packet(2, 2, command))      # COMMAND
        _req_id, _typ, body = _read_packet(ch)
        return body
    finally:
        ch.close()


def main() -> int:
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 1
    mode, rest = args[0], args[1:]
    cli = connect()
    try:
        if mode == "exec":
            print(ps(cli, " ".join(rest)))
        elif mode == "log":
            n = rest[0] if rest else "40"
            print(ps(cli, f"[Console]::OutputEncoding=[Text.Encoding]::UTF8; "
                          f"Get-Content '{LOG_PATH}' -Encoding UTF8 -Tail {n}"))
        elif mode == "rcon":
            cmd = " ".join(rest)
            out = run_rcon(cli, cmd)
            print(out)
        else:
            print("未知模式:", mode)
            return 1
    finally:
        cli.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
