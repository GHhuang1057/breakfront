"""MC 主机本地编译通道（快速热重启）—— 绕开 CI + 客户端包下载。

子命令：
  build          在服务器上编译 core（后台写日志，前台轮询到结束）
  log [n]        查看编译日志尾部
  deploy         用刚编译的 jar 替换服务端 mods 并重启服务端
  status         查看编译/部署状态

背景（为什么这样做）：现有热更每次都要从 GitHub Release 下载**整个客户端 mods 包**
（update.log 实测 5.5 分钟），而只想验证一行服务端代码改动时完全没必要。
MC 主机可直连 github/maven 且已有 JDK21（J:\\bfserver\\jdk21），故可直接本地编译。
"""
from __future__ import annotations

import base64
import sys
import time

sys.path.insert(0, "scripts")
from mc_remote import connect, ps  # noqa: E402

BUILD_DIR = r"J:\bfbuild\breakfront"
LOG = r"J:\bfbuild\build.log"
BAT = r"J:\bfbuild\build.bat"
MODS = r"J:\bfserver\server\mods"
JDK = r"J:\bfserver\jdk21"

BUILD_BAT = r"""@echo off
set JAVA_HOME=J:\bfserver\jdk21
cd /d J:\bfbuild\breakfront
call J:\bfbuild\gradle-9.5.0\bin\gradle.bat :core:build -x test > J:\bfbuild\build.log 2>&1
echo EXIT=%ERRORLEVEL% >> J:\bfbuild\build.log
"""

# 仓库里**没有 gradle wrapper**（无 gradlew/gradlew.bat），所以服务器端需自备 Gradle。
# 版本对齐 CI（.github/workflows/build.yml 用 gradle-version: '9.5.0'）。
GRADLE_VER = "9.5.0"
GRADLE_ZIP = rf"J:\bfbuild\gradle-{GRADLE_VER}-bin.zip"
GRADLE_HOME = rf"J:\bfbuild\gradle-{GRADLE_VER}"
GRADLE_URL = f"https://mirrors.cloud.tencent.com/gradle/gradle-{GRADLE_VER}-bin.zip"
# ⚠️ 实测 services.gradle.org 在这台国内服务器上只有 ~140KB/s（130MB 要 15 分钟），
#    腾讯云镜像同文件可达数十 MB/s —— 国内服务器一律走镜像。
GRADLE_LOG = r"J:\bfbuild\gradle-fetch.log"
FETCH_BAT = rf"""@echo off
curl.exe -L --retry 3 -o "{GRADLE_ZIP}" "{GRADLE_URL}" > "{GRADLE_LOG}" 2>&1
echo CURL_EXIT=%ERRORLEVEL% >> "{GRADLE_LOG}"
powershell -NoProfile -Command "Expand-Archive -Path '{GRADLE_ZIP}' -DestinationPath 'J:\bfbuild' -Force" >> "{GRADLE_LOG}" 2>&1
echo UNZIP_EXIT=%ERRORLEVEL% >> "{GRADLE_LOG}"
"""


def _write_remote(cli, path: str, content: str) -> None:
    """用 base64 写文件，彻底避开引号/换行/编码转义问题。"""
    b64 = base64.b64encode(content.encode("utf-8")).decode("ascii")
    ps(cli, f"$b=[Convert]::FromBase64String('{b64}'); "
            f"[IO.File]::WriteAllBytes('{path}',$b); Write-Output 'written'")


write_remote = _write_remote


def cmd_fetch_gradle(cli) -> int:
    """在服务器上下载并解压 Gradle（无 wrapper 时的自备工具链）。"""
    ready = ps(cli, f"Test-Path '{GRADLE_HOME}\\bin\\gradle.bat'").strip()
    if ready.lower().startswith("true"):
        print("[=] Gradle 已就绪:", GRADLE_HOME)
        return 0
    _write_remote(cli, r"J:\bfbuild\fetch_gradle.bat", FETCH_BAT)
    ps(cli, f"if (Test-Path '{GRADLE_LOG}') {{ Remove-Item '{GRADLE_LOG}' -Force }}; "
            f"Start-Process -FilePath 'cmd.exe' -ArgumentList '/c','J:\\bfbuild\\fetch_gradle.bat' "
            f"-WindowStyle Hidden; 'started'")
    print("[*] 已启动 Gradle 下载（约 130MB，由服务器自行下载，不占用本地会话）")
    for i in range(60):          # 最多等 20 分钟
        time.sleep(20)
        out = ps(cli, f"$z = if (Test-Path '{GRADLE_ZIP}') {{ (Get-Item '{GRADLE_ZIP}').Length }} else {{ 0 }}; "
                      f"$ok = Test-Path '{GRADLE_HOME}\\bin\\gradle.bat'; "
                      f"Write-Output ('zip=' + $z + ' ready=' + $ok); "
                      f"if (Test-Path '{GRADLE_LOG}') {{ Get-Content '{GRADLE_LOG}' -Encoding UTF8 -Tail 3 }}")
        print(f"--- {(i + 1) * 20}s --- {out.strip()}")
        if "ready=True" in out:
            print("[✓] Gradle 就绪")
            return 0
        if "UNZIP_EXIT" in out:
            print("[?] 解压阶段结束，检查 ready 状态")
    return 2


def tail(cli, n: int = 15) -> str:
    return ps(cli, f"if (Test-Path '{LOG}') {{ "
                   f"(Get-Content '{LOG}' -Encoding UTF8 -Tail {n}) -join [char]10 }} "
                   f"else {{ 'no log' }}")


def cmd_build(cli) -> int:
    write_remote(cli, BAT, BUILD_BAT)
    # ⚠️ 启动方式踩了两坑：① `Start-Process <bat>` 直接跑 .bat 不会执行（无进程、无日志）；
    #    ② 经 `cmd /c` 虽可执行但构建进程会随 SSH 会话/父窗口消失。最终用**计划任务**：
    #    独立于会话、原生长任务执行、且任务执行 .bat 自动经 cmd，无需额外包装。
    ps(cli, f"if (Test-Path '{LOG}') {{ Remove-Item '{LOG}' -Force }}; "
            f"$a = New-ScheduledTaskAction -Execute '{BAT}'; "
            f"Register-ScheduledTask -TaskName 'BFHotBuild' -Action $a -RunLevel Highest -Force | Out-Null; "
            f"Start-ScheduledTask -TaskName 'BFHotBuild'; Write-Output 'started'")
    print("[*] 已在服务器后台启动编译（首次需下载 Gradle 与依赖，可能 5-10 分钟）")
    for i in range(60):          # 最多等 20 分钟
        time.sleep(20)
        out = tail(cli, 6)
        print(f"--- {(i + 1) * 20}s ---\n{out}")
        if "EXIT=" in out:
            ok = "EXIT=0" in out
            print("[✓] 编译成功" if ok else "[✗] 编译失败")
            return 0 if ok else 1
    print("[!] 超时未结束")
    return 2


def cmd_log(cli) -> int:
    print(tail(cli, 30))
    return 0


def cmd_status(cli) -> int:
    print(ps(cli, f"Write-Output '--- jar 产物 ---'; "
                  f"Get-ChildItem '{BUILD_DIR}\\core\\build\\libs' -ErrorAction SilentlyContinue | "
                  f"Select-Object Name,Length,LastWriteTime | Format-Table -AutoSize | Out-String; "
                  f"Write-Output '--- 服务端 mods 里的 breakfront ---'; "
                  f"Get-ChildItem '{MODS}' -Filter 'breakfront*' | "
                  f"Select-Object Name,Length,LastWriteTime | Format-Table -AutoSize | Out-String"))
    return 0


def cmd_deploy(cli, resume_updater: bool = False) -> int:
    """把编译产物部署到服务端并重启（开发模式的完整热重启）。"""
    from mc_remote import run_rcon

    jar = ps(cli, f"(Get-ChildItem '{BUILD_DIR}\\core\\build\\libs' -Filter 'breakfront-*.jar' | "
                  f"Where-Object {{ $_.Name -notlike '*sources*' -and $_.Name -notlike '*dev*' }} | "
                  f"Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName").strip()
    if not jar or "\\" not in jar:
        print("[✗] 未找到编译产物，先跑 build")
        return 1
    print("[*] 编译产物:", jar)

    print("[*] 停热更守卫（否则它会下载 release 覆盖本地 jar）…")
    ps(cli, "Stop-ScheduledTask -TaskName 'BreakfrontUpdater' -ErrorAction SilentlyContinue; "
            "Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*bf_autoupdater*' -or "
            "$_.CommandLine -like '*bf_updater_loop*' } | "
            "ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }; "
            "Write-Output 'updater stopped'")

    print("[*] 停服务端（RCON stop 优先）…")
    try:
        run_rcon(cli, "stop")
    except Exception as e:  # noqa: BLE001
        print("  RCON stop 失败（继续强杀）:", e)
    killed = False
    for _ in range(12):
        time.sleep(5)
        n = ps(cli, "@(Get-CimInstance Win32_Process | Where-Object { $_.Name -match '^java' -and "
                    "$_.CommandLine -match 'fabric-server-launch' }).Count").strip()
        if n == "0":
            print("  [✓] 服务端已退出")
            break
    if not killed:
        ps(cli, "Get-CimInstance Win32_Process | Where-Object { $_.Name -match '^java' -and "
                "$_.CommandLine -match 'fabric-server-launch' } | "
                "ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }; "
                "Start-Sleep -Seconds 4; Write-Output 'force killed'")
        print("  [!] 已强杀")

    print("[*] 替换 jar（旧文件备份到 J:\\bfbuild\\mods_backup）…")
    script = ("$mods='" + MODS + "'; $src='" + jar + "'; $bak='J:\\bfbuild\\mods_backup'; "
              "New-Item -ItemType Directory -Force -Path $bak | Out-Null; "
              "Get-ChildItem $mods -Filter 'breakfront-*.jar' | ForEach-Object { "
              "Copy-Item $_.FullName (Join-Path $bak $_.Name) -Force; Remove-Item $_.FullName -Force }; "
              "Copy-Item $src (Join-Path $mods (Split-Path $src -Leaf)) -Force; "
              "Get-ChildItem $mods -Filter 'breakfront*.jar' | "
              "Select-Object Name,@{n='MB';e={[math]::Round($_.Length/1MB,2)}},LastWriteTime | "
              "Format-Table -AutoSize | Out-String")
    print(ps(cli, script))

    print("[*] 启动服务端…")
    # ⚠️ 启动服务端**必须**用「计划任务执行 start.bat」，两个坑都踩过：
    #   ① Start-Process 直接启 java：其 stdin 连着 SSH 会话，exec 命令一返回 stdin 就 EOF，
    #      而 Minecraft 服务端读到 stdin EOF 会**自行停止** → 表现为"启动后约 1 分钟又没了"。
    #   ② 经 `cmd /c start.bat -WindowStyle Hidden` 在本 SSH 会话下干脆起不来。
    #   start.bat 末尾的 `pause` 让 cmd 持有 stdin，计划任务又独立于会话 → 稳定（实测 90s+ 存活）。
    ps(cli, "Remove-ScheduledTask -TaskName 'BFServerOnce' -ErrorAction SilentlyContinue; "
            "$a = New-ScheduledTaskAction -Execute 'J:\\bfserver\\server\\start.bat'; "
            "Register-ScheduledTask -TaskName 'BFServerOnce' -Action $a -RunLevel Highest -Force | Out-Null; "
            "Start-ScheduledTask -TaskName 'BFServerOnce'; Write-Output 'task-started'")
    t0 = time.time()
    for _ in range(30):
        time.sleep(5)
        n = ps(cli, "@(Get-NetTCPConnection -LocalPort 25565 -State Listen -ErrorAction SilentlyContinue).Count").strip()
        if n != "0":
            print(f"[✓] 服务端已监听 25565（耗时 {int(time.time() - t0)}s）")
            break
    else:
        print("[!] 25565 未监听，请查 J:\\bfserver\\server\\logs\\latest.log")

    if resume_updater:
        ps(cli, "Start-ScheduledTask -TaskName 'BreakfrontUpdater' -ErrorAction SilentlyContinue; 'updater resumed'")
        print("[*] 热更守卫已恢复")
    else:
        print("[i] 热更守卫保持停用（开发模式）。恢复命令：deploy-resume-updater")
    return 0


def cmd_updater(cli, on: bool) -> int:
    if on:
        ps(cli, "Start-ScheduledTask -TaskName 'BreakfrontUpdater' -ErrorAction SilentlyContinue; 'resumed'")
        print("[✓] 热更守卫已启用")
    else:
        ps(cli, "Stop-ScheduledTask -TaskName 'BreakfrontUpdater' -ErrorAction SilentlyContinue; 'stopped'")
        print("[✓] 热更守卫已停用")
    return 0


def main() -> int:
    args = sys.argv[1:]
    mode = args[0] if args else "status"
    cli = connect()
    try:
        if mode == "build":
            return cmd_build(cli)
        if mode == "fetch-gradle":
            return cmd_fetch_gradle(cli)
        if mode == "log":
            return cmd_log(cli)
        if mode == "status":
            return cmd_status(cli)
        if mode == "deploy":
            return cmd_deploy(cli, resume_updater=("--resume" in args))
        if mode == "updater-off":
            return cmd_updater(cli, False)
        if mode == "updater-on":
            return cmd_updater(cli, True)
        print("未知子命令:", mode)
        return 1
    finally:
        cli.close()


if __name__ == "__main__":
    raise SystemExit(main())
