#!/usr/bin/env python3
"""MC host one-shot: install pubkey + switch frpc to Beijing + verify."""
import os, sys, time
import paramiko

HOST = "103.27.76.17"
PW = os.environ["MC_PW"]
PUBKEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIE/6nCNYyY8lIukcoDA/bbRwSnrYDcGvK9z7AjZ6vVGJ bf-deploy@workbuddy-agent"

CMDS = [
    ("hostname", 15),
    # pubkey install
    ("Add-Content -Path 'C:\\ProgramData\\ssh\\administrators_authorized_keys' -Value '" + PUBKEY + "' -Encoding ascii; "
     "icacls 'C:\\ProgramData\\ssh\\administrators_authorized_keys' /inheritance:r /grant 'SYSTEM:F' /grant 'BUILTIN\\Administrators:F' | Out-Null; Write-Output PUBKEY-OK", 20),
    # frpc switch
    ("Copy-Item 'C:\\bfdeploy\\frp\\frpc.toml' 'C:\\bfdeploy\\frp\\frpc.toml.bak_hk132' -Force; "
     "(Get-Content 'C:\\bfdeploy\\frp\\frpc.toml' -Raw) -replace '103\\.24\\.217\\.132','8.141.114.60' | Set-Content 'C:\\bfdeploy\\frp\\frpc.toml' -Encoding UTF8; "
     "Write-Output CONFIG-SWITCHED", 20),
    ("schtasks /End /TN BreakfrontFrpc 2>$null; Start-Sleep 2; schtasks /Run /TN BreakfrontFrpc | Out-Null; Start-Sleep 8; Write-Output TASK-RESTARTED", 30),
    ("Write-Output '--config--'; Get-Content 'C:\\bfdeploy\\frp\\frpc.toml' | Select-String serverAddr; "
     "Write-Output '--frpc-proc--'; Get-Process frpc -ErrorAction SilentlyContinue | Select-Object Id,ProcessName | Format-Table -HideTableHeaders | Out-String; "
     "Write-Output '--mc-server-port--'; netstat -ano | Select-String ':25565.*LISTENING'", 30),
]


def main():
    s = paramiko.SSHClient()
    s.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    s.connect(HOST, 22, "Administrator", PW, timeout=20, banner_timeout=20, auth_timeout=20,
              allow_agent=False, look_for_keys=False)
    print("=== connected to", HOST, "===")
    for cmd, t in CMDS:
        _, out, err = s.exec_command(cmd, timeout=t)
        o = out.read().decode("utf-8", "replace").strip()
        e = err.read().decode("utf-8", "replace").strip()
        print("$", cmd[:80], "...")
        if o:
            print(o)
        if e:
            print("[stderr]", e[:300])
        print("-" * 40)
    s.close()
    print("=== done ===")


if __name__ == "__main__":
    main()
