"""MC 主机本地编译通道（快速热重启）—— 绕开 CI + 客户端包下载。

子命令：
  pull           把编译机仓库同步到远端 main（编译前必做）
  build          在服务器上编译 core+client（后台写日志，前台轮询到结束）
  log [n]        查看编译日志尾部
  deploy         用刚编译的 jar 替换服务端 mods 并重启服务端
  status         查看编译/部署状态
  squaremap      下载/安装 squaremap 模组到服务端 mods
  squaremap-config  改写 squaremap 的 internal-webserver bind/port
  sqm-status     查看真实俯瞰图瓦片渲染进度
  sqm-render     对主世界发起 fullrender 并轮询到结束
  sqm-radius [半径]  只渲染战场周边（radiusrender，默认取 squaremap spawn）

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
call J:\bfbuild\gradle-9.5.0\bin\gradle.bat build -x test > J:\bfbuild\build.log 2>&1
echo EXIT=%ERRORLEVEL% >> J:\bfbuild\build.log
"""
# ⚠️ 用 `build`（全模块）而非 `:core:build`：只编 core 会漏掉 client 侧的改动
# （客户端 HUD/mixin 全是 Java，编不过就得等 CI 才发现）。客户端产物仅用于「编译校验」，
# 部署到服务端时仍只取 core 的 jar（见 cmd_deploy）。

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


def _mcsm_bf_uuid(cli) -> str:
    """在 MCSM 面板里找到 Breakfront 实例的 uuid；找不到返回空串。"""
    try:
        from mcsm_setup_bf import inst_list, find_by_nick, NICKNAME
        bf = find_by_nick(inst_list(cli), NICKNAME)
        return bf["instanceUuid"] if bf else ""
    except Exception:  # noqa: BLE001
        return ""


def _mcsm_action(cli, action: str) -> bool:
    """经 MCSM 面板对 Breakfront 实例执行 open/stop/restart/kill。"""
    uuid = _mcsm_bf_uuid(cli)
    if not uuid:
        return False
    try:
        from mcsm_setup_bf import inst_action
        h, _b = inst_action(cli, action, uuid)
        return " 200" in (h.splitlines()[0] if h else "")
    except Exception:  # noqa: BLE001
        return False


def _mcsm_start() -> bool:
    """独立建一条 SSH 连接去面板点「启动」（避免复用可能已僵死的 cli）。"""
    try:
        from mc_remote import connect
        from mcsm_setup_bf import inst_list, find_by_nick, NICKNAME, inst_action
        c = connect()
        try:
            bf = find_by_nick(inst_list(c), NICKNAME)
            if not bf:
                print("  [!] 面板里没有 Breakfront 实例")
                return False
            h, _b = inst_action(c, "open", bf["instanceUuid"])
            ok = " 200" in (h.splitlines()[0] if h else "")
            print("  面板 open:", "OK" if ok else (h.splitlines()[0] if h else "无响应"))
            return ok
        finally:
            c.close()
    except Exception as e:  # noqa: BLE001
        print("  [!] 面板启动失败:", type(e).__name__, e)
        return False


def cmd_deploy(cli, resume_updater: bool = False) -> int:
    """把编译产物部署到服务端并重启（开发模式的完整热重启）。

    ⚠️ 2026-09-10 起服务端由 MCSM 面板托管，本命令的停/启都走面板，
       不再自行拉进程（详见下方启动段注释）。
    """
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

    print("[*] 停服务端（走 MCSM 面板，保证面板状态与实际一致）…")
    mcsm_ok = _mcsm_action(cli, "stop")
    if not mcsm_ok:
        print("  面板 stop 不可用，回退 RCON stop")
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
            killed = True
            break
    if not killed:
        print("  [!] java 未自行退出，强杀")
    # ⚠️ 无论 java 是否自行退出，都要清掉「卡在 pause 的 start.bat cmd」：
    #    start.bat 末尾是 pause，java 一退出 cmd 就会挂在那里 → BFServerOnce 任务一直是
    #    Running 状态 → Windows 默认 MultipleInstances=IgnoreNew，后续 Start-ScheduledTask
    #    直接变成空操作（实测：部署日志说"已启动"，实际 25565 永远不监听）。
    ps(cli, "Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'cmd.exe' -and "
            "$_.CommandLine -like '*start.bat*' } | "
            "ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }; "
            "Get-CimInstance Win32_Process | Where-Object { $_.Name -match '^java' -and "
            "$_.CommandLine -match 'fabric-server-launch' } | "
            "ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }; "
            "Stop-ScheduledTask -TaskName 'BFServerOnce' -ErrorAction SilentlyContinue; "
            "Start-Sleep -Seconds 3; Write-Output 'cleaned'")

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
    # ⚠️ 2026-09-10 起服务端启停已交给 MCSM 面板托管（见 scripts/mcsm_takeover.py），
    #    这里**必须**走面板 open，不能再自行 Start-Process / 计划任务拉 java：
    #    · 自行启 java：stdin 连着 SSH 会话，exec 一返回 stdin 就 EOF，MC 读到 EOF 会自行停止；
    #      且会与 MCSM 抢 25565 / 世界锁，还会让 MCSM 里的实例显示为「已停止」而实际在跑。
    #    · MCSM 用 pty 持有 stdin，天然没有 EOF 问题，面板状态也才准。
    started = _mcsm_start()
    t0 = time.time()
    up = False
    for _ in range(40):          # 最多等 200s（首次启动要大世界加载）
        time.sleep(5)
        n = ps(cli, "@(Get-NetTCPConnection -LocalPort 25565 -State Listen -ErrorAction SilentlyContinue).Count").strip()
        if n != "0":
            print(f"[✓] 服务端已监听 25565（耗时 {int(time.time() - t0)}s）")
            up = True
            break
    if not up:
        print("[!] 25565 未监听。诊断：")
        nproc = ps(cli, "@(Get-CimInstance Win32_Process | Where-Object { $_.CommandLine "
                        "-match 'fabric-server-launch' }).Count").strip()
        print("  java 进程数 =", nproc,
              "→ 可能仍在启动" if nproc not in ("", "0") else "→ 启动失败（面板里看控制台输出）")

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


def cmd_pull(cli) -> int:
    """把编译机仓库同步到远端 main —— 编译前**必做**，否则编出来的是旧代码。

    用 fetch + reset --hard 而非 pull：编译机上不该有本地改动，reset 能顺带清掉
    上一次构建残留（例如手工改动），保证「编的就是远端那份」。
    """
    out = ps(cli, f"cd '{BUILD_DIR}'; "
                  f"git fetch origin main 2>&1 | Select-Object -Last 3; "
                  f"git reset --hard FETCH_HEAD 2>&1 | Select-Object -Last 2; "
                  f"Write-Output '--- HEAD ---'; git log --oneline -1")
    print(out.strip())
    ok = "--- HEAD ---" in out
    print("[✓] 已同步" if ok else "[✗] 同步失败（检查编译机的 GitHub 凭据）")
    return 0 if ok else 1


# ---------------------------------------------------------------------------
# squaremap：管理台「真实 2D 俯瞰图」的数据源
# ---------------------------------------------------------------------------
# 版本由 pack/tools/mod_pins.json 钉住（slug=squaremap, env=server, expect=1.3.2）。
# 服务器自带外网 + 可直连 Modrinth CDN，故让服务器自己下载（8.4MB，不走本地转存）。
# 依赖 cloud / adventure-platform-fabric 已全部 JiJ 内嵌在 jar 里（已核 fabric.mod.json
# 的 jars 段），无需额外补依赖。
SQM_JAR = "squaremap-fabric-mc1.21.1-1.3.2.jar"
SQM_URL = ("https://cdn.modrinth.com/data/PFb7ZqK6/versions/RerxbGKf/"
           "squaremap-fabric-mc1.21.1-1.3.2.jar")
SQM_SIZE = 8462749
# ⚠️ 实测路径是 **服务端根目录下的 squaremap/**，不是 config/squaremap/ ——
#    写错会导致 cmd_squaremap_config 静默失败（Test-Path 为 False 时只是打印告警）。
SQM_CFG = r"J:\bfserver\server\squaremap\config.yml"
SQM_WEB = r"J:\bfserver\server\squaremap\web"
SQM_TILES = SQM_WEB + r"\tiles"
SQM_WORLD_ARG = "minecraft:overworld"   # squaremap 命令里世界用「标识符」，不是目录名 world


def cmd_squaremap(cli) -> int:
    """安装 squaremap 到服务端 mods（按体积校验）。"""
    dst = f"{MODS}\\{SQM_JAR}"
    ps(cli, f"if (Test-Path '{dst}') {{ Remove-Item '{dst}' -Force }}")
    print("[*] 从 Modrinth CDN 下载 squaremap …")
    out = ps(cli, f"curl.exe -sL --retry 3 -o '{dst}' '{SQM_URL}'; "
                  f"if (Test-Path '{dst}') {{ (Get-Item '{dst}').Length }} else {{ 0 }}")
    try:
        size = int(out.strip().splitlines()[-1])
    except Exception:  # noqa: BLE001
        size = 0
    if size != SQM_SIZE:
        print(f"[✗] 体积不符：{size} != {SQM_SIZE}（下载失败或 CDN 变更）")
        return 1
    print(f"[✓] squaremap 已就位（{size} 字节）")
    return 0


SQM_CFG_PS = """$ErrorActionPreference='Stop'
$p='{cfg}'
$lines=Get-Content $p -Encoding UTF8
$out=New-Object System.Collections.Generic.List[string]
$base=-1; $done=$false
foreach($l in $lines){{
  if($base -lt 0){{
    if($l -match '^(\\s*)internal-webserver:\\s*$'){{ $base=$matches[1].Length }}
    $out.Add($l); continue
  }}
  if($l.Trim().Length -eq 0){{ $out.Add($l); continue }}
  $ind=$l.Length - $l.TrimStart().Length
  if($ind -le $base){{ $base=-1; $out.Add($l); continue }}   # 缩进回到块外 → 结束
  if($l -match '^(\\s*)bind:.*'){{ $out.Add($matches[1]+'bind: {bind}'); $done=$true; continue }}
  if($l -match '^(\\s*)port:.*'){{ $out.Add($matches[1]+'port: {port}'); continue }}
  $out.Add($l)
}}
[IO.File]::WriteAllText($p, ($out -join "`n"), (New-Object System.Text.UTF8Encoding($false)))
Write-Output ($(if($done){{'PATCHED'}}else{{'NOT-FOUND'}}))
"""


def cmd_squaremap_config(cli, bind: str = "127.0.0.1", port: int = 8080) -> int:
    """把 squaremap 的 web 监听改到 内网地址:端口。

    先决条件：服务端**至少启动过一次**（squaremap 才会生成 config.yml）。

    ⚠️ 只在 `settings.internal-webserver:` 块内替换 bind/port。
    不能用「全局正则」或「遇到任意非空行就退出块」——配置里存在大量缩进相同的
    同名键（`port:`/`enabled:` 在 mock 中随处可见），块边界必须按**缩进深度**判定。
    改完需 `/squaremap reload` 或重启才生效（Jetty 是启动时绑定的），
    所以这里默认只落盘、不强推 reload（避免把当前可用的监听弄挂）。
    """
    if not ps(cli, f"Test-Path '{SQM_CFG}'").strip().lower().startswith("true"):
        print(f"[✗] 未找到 {SQM_CFG} —— 先启动一次服务端让它生成默认配置")
        return 1
    script = SQM_CFG_PS.format(cfg=SQM_CFG, bind=bind, port=port)
    _write_remote(cli, r"J:\bfbuild\_sqm_cfg.ps1", script)
    res = ps(cli, r"& 'J:\bfbuild\_sqm_cfg.ps1'")
    print("[*] 结果:", res.strip())
    print("--- 现行 internal-webserver 段 ---")
    print(ps(cli, f"$lines=Get-Content '{SQM_CFG}' -Encoding UTF8; "
                  f"$i=[array]::IndexOf($lines,'    internal-webserver:'); "
                  f"if($i -lt 0){{$i=0}}; ($lines | Select-Object -Skip $i -First 5) -join \"`n\""))
    print("[!] 下次重启服务端（或 `/squaremap reload`）后监听地址生效")
    return 0


def _sqm_tile_stats(cli) -> str:
    """返回瓦片数量与当前最大的缩放层级（用于判断渲染是否产出）。"""
    return ps(cli, f"$png=Get-ChildItem '{SQM_TILES}' -Recurse -Filter *.png "
                   f"-ErrorAction SilentlyContinue; "
                   f"$n=($png | Measure-Object).Count; "
                   f"$zs=($png | ForEach-Object {{ $_.DirectoryName }} | Sort-Object -Unique); "
                   f"Write-Output ('TILES=' + $n); "
                   f"foreach($z in $zs){{ Write-Output ('ZDIR=' + $z) }}")


def cmd_squaremap_status(cli) -> int:
    """看看 squaremap 渲染到什么程度了。

    注：squaremap **没有** `progress` 子命令（只有 pauserender/progresslogging/
    fullrender/cancelrender/radiusrender/reload/hide）。进度只能看日志
    （config 里 render-progress-logging.enabled=true 会每秒打印一行）。
    """
    print(_sqm_tile_stats(cli).strip() or "(空)")
    print("--- 渲染日志（尾部）---")
    print(ps(cli, r"Select-String -Path 'J:\bfserver\server\logs\latest.log' "
                  r"-Pattern 'squaremap|Rendering|render' | "
                  r"Select-Object -Last 12 | ForEach-Object { $_.Line }").strip() or "(无)")
    return 0


def cmd_squaremap_radius(cli, radius: int = 512, center: str | None = None) -> int:
    """只渲染战场周边（比 fullrender 快得多）：radiusrender <world> <半径> [中心]。

    中心默认取 squaremap 元数据里的 spawn（= 地图导入点），那正是战场所在。
    """
    from mc_remote import run_rcon  # noqa: PLC0415
    if center is None:
        txt = ps(cli, f"Get-Content '{SQM_TILES}\\{SQM_WORLD_ARG.replace(':', '_')}"
                      f"\\settings.json' -Raw")
        import json as _json  # noqa: PLC0415
        try:
            sp = _json.loads(txt)["spawn"]
            center = f"{sp['x']} {sp['z']}"
        except Exception:  # noqa: BLE001
            center = ""
    cmd = f"squaremap radiusrender {SQM_WORLD_ARG} {radius}"
    if center:
        cmd += f" {center}"
    print("[*]", cmd)
    print(run_rcon(cli, cmd).strip() or "(已接受)")
    return 0


def cmd_squaremap_render(cli) -> int:
    """对主世界发起 fullrender 并轮询到结束（管理台真实俯瞰图的数据来源）。

    squaremap 的世界参数是**标识符**（minecraft:overworld），不是世界目录名 world。
    渲染是异步的：这里轮询瓦片数量，直到连续若干轮不再增长。
    """
    from mc_remote import run_rcon  # noqa: PLC0415
    print(f"[*] 对 {SQM_WORLD_ARG} 发起 fullrender …")
    print(run_rcon(cli, f"squaremap fullrender {SQM_WORLD_ARG}").strip() or "(已接受)")
    last = -1
    stable = 0
    for i in range(90):  # 最多 ~15 分钟
        time.sleep(10)
        n = 0
        for line in _sqm_tile_stats(cli).splitlines():
            if line.startswith("TILES="):
                try:
                    n = int(line.split("=", 1)[1].strip())
                except ValueError:
                    n = 0
        print(f"  [{i * 10:>4}s] 瓦片 {n}")
        if n == last:
            stable += 1
            if stable >= 3 and n > 0:
                print("[✓] 渲染已收敛（连续 3 轮无新增）")
                break
        else:
            stable = 0
        last = n
    print(_sqm_tile_stats(cli).strip())
    return 0 if last > 0 else 1


def main() -> int:
    args = sys.argv[1:]
    mode = args[0] if args else "status"
    cli = connect()
    try:
        if mode == "squaremap":
            return cmd_squaremap(cli)
        if mode == "squaremap-config":
            return cmd_squaremap_config(cli)
        if mode == "sqm-status":
            return cmd_squaremap_status(cli)
        if mode == "sqm-render":
            return cmd_squaremap_render(cli)
        if mode == "sqm-radius":
            return cmd_squaremap_radius(cli, int(args[1]) if len(args) > 1 else 512)
        if mode == "pull":
            return cmd_pull(cli)
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
