#!/usr/bin/env python3
"""Beijing 8.141.114.60 frps setup (mirror of HK 132 config, frp 0.71.0)."""
import os, sys, time
import paramiko

HOST = "8.141.114.60"
PW = os.environ["BEIJING_PW"]
TOKEN = "978b3d84ea4ffc7c2aaaa2f6766f7207"
PUBKEYS = [
    r"C:\Users\huang\.ssh\bf_mc_geekhonize.pub",
    r"C:\Users\huang\.ssh\id_deploy.pub",
]

FRPS_TOML = '''bindPort = 7000
auth.method = "token"
auth.token = "%s"
''' % TOKEN

UNIT = '''[Unit]
Description=frp server
After=network.target

[Service]
Type=simple
ExecStart=/opt/frp/frps -c /opt/frp/frps.toml
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
'''


def run(ssh, cmd, timeout=120):
    _, out, err = ssh.exec_command(cmd, timeout=timeout)
    o = out.read().decode("utf-8", "replace").strip()
    e = err.read().decode("utf-8", "replace").strip()
    return o, e


def main():
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(HOST, 22, "root", PW, timeout=20, banner_timeout=20, auth_timeout=20)
    print("=== connected ===")

    o, _ = run(ssh, "uname -m; cat /etc/os-release | head -2; nproc; free -m | head -2; df -h / | tail -1")
    print(o)

    # install frp 0.71.0
    o, _ = run(ssh, "test -x /opt/frp/frps && /opt/frp/frps --version || echo MISSING")
    print("frps existing:", o)
    if "0.71.0" not in o:
        urls = [
            "https://github.com/fatedier/frp/releases/download/v0.71.0/frp_0.71.0_linux_amd64.tar.gz",
            "https://ghproxy.net/https://github.com/fatedier/frp/releases/download/v0.71.0/frp_0.71.0_linux_amd64.tar.gz",
            "https://gh-proxy.com/https://github.com/fatedier/frp/releases/download/v0.71.0/frp_0.71.0_linux_amd64.tar.gz",
        ]
        ok = False
        for u in urls:
            print("try download:", u)
            o, e = run(ssh, "curl -fsSL --connect-timeout 15 --max-time 180 -o /tmp/frp.tgz '%s' && ls -la /tmp/frp.tgz" % u, timeout=200)
            print(o or e)
            if "/tmp/frp.tgz" in o:
                ok = True
                break
        if not ok:
            print("!! download failed"); sys.exit(2)
        o, e = run(ssh, "mkdir -p /opt/frp && tar -xzf /tmp/frp.tgz -C /tmp && cp /tmp/frp_0.71.0_linux_amd64/frps /opt/frp/frps && chmod +x /opt/frp/frps && /opt/frp/frps --version")
        print("installed:", o, e)

    # config + unit
    sftp = ssh.open_sftp()
    with sftp.open("/opt/frp/frps.toml", "w") as f:
        f.write(FRPS_TOML)
    with sftp.open("/etc/systemd/system/frps.service", "w") as f:
        f.write(UNIT)
    sftp.close()

    # authorized keys for future passwordless ops
    pubs = []
    for p in PUBKEYS:
        if os.path.exists(p):
            pubs.append(open(p).read().strip())
    if pubs:
        add = ";".join("grep -qF '%s' /root/.ssh/authorized_keys 2>/dev/null || echo '%s' >> /root/.ssh/authorized_keys" % (k, k) for k in pubs)
        run(ssh, "mkdir -p /root/.ssh && chmod 700 /root/.ssh && " + add)

    # firewall
    o, e = run(ssh, "ufw status 2>/dev/null | head -3; ufw allow 7000/tcp; ufw allow 25565/tcp; ufw allow 10022/tcp 2>/dev/null; echo FW_DONE")
    print(o, e)

    o, e = run(ssh, "systemctl daemon-reload && systemctl enable frps --now && sleep 1 && systemctl is-active frps; ss -tlnp | grep :7000")
    print("frps status:", o, e)

    # keepalive probe of key ports from server itself
    o, _ = run(ssh, "ss -tlnp | grep -E ':(7000|25565|10022)' || true")
    print("listening:", o)
    ssh.close()
    print("=== done ===")


if __name__ == "__main__":
    main()
