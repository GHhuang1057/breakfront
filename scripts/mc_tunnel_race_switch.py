#!/usr/bin/env python3
"""Race the flapping 132:10022 tunnel: when it opens, immediately switch frpc to Beijing + install pubkey."""
import paramiko, socket, time, sys

TUN = ("103.24.217.132", 10022)
PUBKEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIE/6nCNYyY8lIukcoDA/bbRwSnrYDcGvK9z7AjZ6vVGJ bf-deploy@workbuddy-agent"

SWITCH_CMD = (
    "Copy-Item 'C:\\bfdeploy\\frp\\frpc.toml' 'C:\\bfdeploy\\frp\\frpc.toml.bak_hk132' -Force; "
    "(Get-Content 'C:\\bfdeploy\\frp\\frpc.toml' -Raw) -replace '103\\.24\\.217\\.132','8.141.114.60' | "
    "Set-Content 'C:\\bfdeploy\\frp\\frpc.toml' -Encoding UTF8; "
    "Write-Output CONFIG-SWITCHED; "
    "Add-Content -Path 'C:\\ProgramData\\ssh\\administrators_authorized_keys' -Value '" + PUBKEY + "' -Encoding ascii; "
    "icacls 'C:\\ProgramData\\ssh\\administrators_authorized_keys' /inheritance:r /grant 'SYSTEM:F' /grant 'BUILTIN\\Administrators:F' | Out-Null; "
    "Write-Output PUBKEY-OK; "
    "schtasks /End /TN BreakfrontFrpc 2>$null; Start-Sleep 2; "
    "schtasks /Run /TN BreakfrontFrpc | Out-Null; Start-Sleep 8; "
    "Write-Output TASK-RESTARTED; "
    "Get-Content 'C:\\bfdeploy\\frp\\frpc.toml' | Select-String serverAddr; "
    "Get-Process frpc -ErrorAction SilentlyContinue | Select-Object Id,ProcessName | Format-Table -HideTableHeaders | Out-String"
)

KEY = r"C:\Users\huang\.ssh\bf_mc_geekhonize"


def tunnel_open():
    s = socket.socket()
    s.settimeout(4)
    try:
        s.connect(TUN)
        s.close()
        return True
    except Exception:
        return False


def do_switch():
    s = paramiko.SSHClient()
    s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    s.connect("103.24.217.132", 10022, "Administrator", key_filename=KEY,
              timeout=15, banner_timeout=15, auth_timeout=15,
              allow_agent=False, look_for_keys=False)
    _, out, err = s.exec_command(SWITCH_CMD, timeout=60)
    o = out.read().decode("utf-8", "replace")
    e = err.read().decode("utf-8", "replace")
    s.close()
    return o, e


def main():
    for i in range(90):  # ~30 min
        if tunnel_open():
            print(f"[{i}] tunnel UP, switching...", flush=True)
            try:
                o, e = do_switch()
                print(o)
                if e:
                    print("[stderr]", e[:500])
                print("SWITCH-ATTEMPT-FINISHED", flush=True)
                return
            except Exception as ex:
                print("switch failed:", repr(ex)[:200], flush=True)
        time.sleep(20)
    print("TUNNEL-NEVER-CAME-BACK", flush=True)


if __name__ == "__main__":
    main()
