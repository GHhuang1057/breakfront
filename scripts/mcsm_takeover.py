r"""把 Breakfront 服务端从「自研计划任务」切换到「MCSM 面板」托管。

【为什么要做，以及为什么不能只做一半】
两套控制源并存会互抢：
  - `BreakfrontServer`：开机触发，跑 `J:\bfserver\server\start.bat`
  - `BreakfrontUpdater`：`C:\bfdeploy\bf_updater_loop.bat` → 每 30s 跑
    `bf_autoupdater.ps1`（健康判据 = java 进程存在 **或** 25565 在监听），
    不健康就拉起服务端，同时还会拉取并部署新版本
一旦 MCSM 停服，守卫会立刻把服务顶回去；且守卫的热更部署会在 MCSM 托管的进程上乱动。

⚠️ **还有个隐藏坑：MCSM 本身没有任何开机自启**（无服务、无计划任务、无启动项，
2026-09-10 实测）。所以只停 `BreakfrontServer` 会引入「重启后服务器不再回来」的回归。
本脚本因此多了一步：建 `MCSMBoot` 开机任务 + 给实例打开 `eventTask.autoStart`，
让「开机 → daemon → 自动拉起实例」这条链补齐。

【执行顺序】（顺序是这个脚本最要紧的部分，改错了会丢存档）
  0) 建 MCSMBoot 开机任务（幂等）；给实例开 autoStart（必要时删→重建）
  1) `Disable-ScheduledTask` BreakfrontServer / BreakfrontUpdater（只禁用、不停止进程）
  2) 服务端在跑就先 `save-all flush` 保数据
     ⚠️ 必须排在「停任务」之前：`Stop-ScheduledTask BreakfrontServer` 会把任务进程树里的
        java 一起**硬杀**（start.bat 起的 java 就挂在该任务树下），前两轮接管就栽在这
  3) `Stop-ScheduledTask BreakfrontUpdater` → 停掉 30s 健康守卫，免得它把服务端顶回来
  4) RCON `stop` 优雅关服 → 等 java 退出 + 25565 释放（超时则强杀）
  5) `Stop-ScheduledTask BreakfrontServer`（清残留进程树）
  6) 面板 open 启动实例
  7) 验证：25565 在监听 / RCON 有响应 / 日志出现 `Done (` / 列表 status=3（运行中）
任一步失败 → 自动回滚（kill 实例 + 重新启用并启动 BreakfrontServer）

⚠️ 两个 API 陷阱（都实测踩过）：
  - 改 autoStart 只能「删除 → 重建」：PUT /api/protected_instance/instance_update
    返回 200 true 但**配置不落盘**（本脚本会先比对，一致才跳过重建以保住 uuid）
  - 校验实例状态**必须走列表接口**：实例运行时 `GET /api/instance`（详情）会稳定
    500「请求超时！请联系管理员检查节点网络状态」；且状态枚举是
    STOP=0 / STOPPING=1 / STARTING=2 / RUNNING=3，**3 才是运行中**（写成 1 会误判）

用法：
  python scripts/mcsm_takeover.py --check      # 只读体检
  python scripts/mcsm_takeover.py --go         # 执行接管
  python scripts/mcsm_takeover.py --fix-boot   # 只重装 MCSMBoot 开机任务
  python scripts/mcsm_takeover.py --rollback   # 手动回滚
"""
from __future__ import annotations

import base64
import json
import sys
import time

sys.path.insert(0, "scripts")
from mc_remote import connect, ps, run_rcon, LOG_PATH  # noqa: E402
import mcsm_api as M  # noqa: E402
from mcsm_setup_bf import (  # noqa: E402
    DAEMON_ID, NICKNAME, BF_CONFIG, inst_list, inst_create, inst_delete,
    find_by_nick, inst_action,
)

KILL_TASKS = ["BreakfrontServer", "BreakfrontUpdater"]
KEEP_TASKS = ["BreakfrontFrpc"]
BOOT_TASK = "MCSMBoot"
BOOT_BAT = r"C:\bfdeploy\mcsmanager_boot.bat"
MCSM_ROOT = M.MCSM_DIR
PORT = 25565

BOOT_BAT_BODY = (
    "@echo off\r\n"
    "rem [Breakfront] Boot up MCSManager (daemon + web) for task MCSMBoot (SYSTEM/BootTrigger).\r\n"
    "rem Idempotent: skip if the port is already listening (safe to run by hand too).\r\n"
    "rem Comments are ASCII-only on purpose: cmd.exe reads .bat as GBK/ANSI, CJK would garble.\r\n"
    "rem No --open: opening a browser at boot makes no sense.\r\n"
    "set MCSM=" + MCSM_ROOT + "\r\n"
    "netstat -ano | findstr /C:\":24444 \" >nul 2>&1\r\n"
    "if errorlevel 1 (\r\n"
    "    cd /d \"%MCSM%\\daemon\"\r\n"
    "    start \"\" node_app.exe --enable-source-maps --max-old-space-size=8192 app.js\r\n"
    ")\r\n"
    "netstat -ano | findstr /C:\":23333 \" >nul 2>&1\r\n"
    "if errorlevel 1 (\r\n"
    "    cd /d \"%MCSM%\\web\"\r\n"
    "    start \"\" node_app.exe --enable-source-maps --max-old-space-size=8192 app.js\r\n"
    ")\r\n"
)


# ─────────────────────────── 探测小工具 ───────────────────────────

def java_procs(cli) -> list[str]:
    """返回形如 'PID|CommandLine' 的服务端 java 进程。"""
    out = ps(cli, "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
                  "ForEach-Object { $_.ProcessId.ToString() + '|' + $_.CommandLine }")
    return [ln.strip() for ln in out.splitlines()
            if ln.strip() and "fabric-server-launch" in ln]


def port_pid(cli, port: int = PORT) -> str:
    return ps(cli, f"Get-NetTCPConnection -LocalPort {port} -State Listen "
                   f"-EA SilentlyContinue | ForEach-Object {{ $_.OwningProcess }}").strip()


def rcon_alive(cli) -> str:
    try:
        r = run_rcon(cli, "list").strip()
        return "" if r.startswith("[rcon]") else r
    except Exception as e:  # noqa: BLE001
        return ""


def log_tail(cli, n: int = 25) -> str:
    return ps(cli, f"Get-Content '{LOG_PATH}' -Encoding UTF8 -Tail {n}")


def wait_until(fn, timeout: int, interval: int = 5, desc: str = ""):
    """轮询直到 fn() 返回真值或超时；返回最后一次结果。"""
    t0, last = time.time(), None
    while time.time() - t0 < timeout:
        last = fn()
        if last:
            print(f"      ✓ {desc}（{int(time.time()-t0)}s）")
            return last
        time.sleep(interval)
    print(f"      ✗ {desc} 超时（{timeout}s）")
    return None


# ─────────────────────────── 各步骤 ───────────────────────────

def ensure_boot_task(cli) -> None:
    print("[0a] 建 MCSMBoot 开机任务（MCSM 原本没有任何开机自启）")
    # 用 base64 传文件内容：单行、不受引号/换行/编码影响
    b64 = base64.b64encode(BOOT_BAT_BODY.encode("utf-8")).decode("ascii")
    ps(cli, f"$b=[Convert]::FromBase64String('{b64}'); "
            f"[System.IO.File]::WriteAllBytes('{BOOT_BAT}',$b)")
    print(f"      boot 脚本 {BOOT_BAT} 写入 = {ps(cli, f'Test-Path {chr(34)}{BOOT_BAT}{chr(34)}').strip()}")

    ps(cli,
       "$act=New-ScheduledTaskAction -Execute '" + BOOT_BAT + "'; "
       "$trg=New-ScheduledTaskTrigger -AtStartup; $trg.Delay='PT30S'; "
       "$prin=New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest; "
       "$set=New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries "
       "-ExecutionTimeLimit (New-TimeSpan -Minutes 5); "
       f"Register-ScheduledTask -TaskName '{BOOT_TASK}' -Action $act -Trigger $trg "
       f"-Principal $prin -Settings $set -Force | Out-Null")
    st = ps(cli, f"(Get-ScheduledTask -TaskName {BOOT_TASK}).State")
    print(f"      任务 MCSMBoot 状态 = {st.strip()}")


def ensure_instance(cli, on: bool = True) -> str:
    """确保实例配置符合 BF_CONFIG（含 autoStart / type）。

    ⚠️ 只能删→重建：PUT /api/protected_instance/instance_update 返回 200 true 但
       配置不落盘（daemon 的 instance.parameters() 对该路径不生效，实测）。
       所以这里先比对，一致就跳过重建以保住 uuid。
    """
    old = find_by_nick(inst_list(cli), NICKNAME)
    if old:
        c = old.get("config") or {}
        cur_auto = (c.get("eventTask") or {}).get("autoStart")
        same = (cur_auto == on and c.get("type") == BF_CONFIG["type"]
                and c.get("startCommand") == BF_CONFIG["startCommand"]
                and c.get("cwd") == BF_CONFIG["cwd"])
        if same:
            print(f"      实例 {old['instanceUuid']} 配置已一致（type={c.get('type')} "
                  f"autoStart={cur_auto}），跳过重建")
            return old["instanceUuid"]
        print(f"      配置不一致（现 type={c.get('type')} autoStart={cur_auto}）→ 删除重建")
        inst_delete(cli, [old["instanceUuid"]], purge=False)
        print(f"      已删除旧实例 {old['instanceUuid']}")
    cfg = json.loads(json.dumps(BF_CONFIG))
    cfg["eventTask"]["autoStart"] = on
    d = inst_create(cli, cfg)
    got = d["config"]
    print(f"      新实例 {d['instanceUuid']} type={got.get('type')} "
          f"autoStart={got['eventTask'].get('autoStart')} "
          f"autoRestart={got['eventTask'].get('autoRestart')}")
    return d["instanceUuid"]


def quiesce(cli, timeout: int = 180) -> bool:
    """停掉旧托管链。**顺序很重要**，见下面注释。"""
    print("[1] 停用旧托管链")
    # 先只 Disable（不 Stop）：Disable 不会杀进程，能立刻止住「开机自启 / 后续触发」
    for n in KILL_TASKS:
        ps(cli, f"Disable-ScheduledTask -TaskName '{n}' -EA SilentlyContinue | Out-Null")
    print("      Disable：" + ", ".join(KILL_TASKS))

    # ⚠️ 关键：`Stop-ScheduledTask BreakfrontServer` 会把**任务进程树里的 java 一起硬杀**
    #    （start.bat 起的 java 属于该任务的树）→ 不先存档就会丢最多 5 分钟进度。
    #    实测前两轮接管第 2 步总报「没有服务端进程在跑」，就是这个硬杀干的。
    if java_procs(cli):
        if not rcon_alive(cli):
            print("      服务端在跑但 RCON 未就绪，先等它就绪（避免无存档硬关）")
            wait_until(lambda: rcon_alive(cli), 240, 6, "服务端就绪")
        if rcon_alive(cli):
            try:
                run_rcon(cli, "save-all flush")
                print("      已下发 save-all flush（保住世界数据）")
                time.sleep(3)
            except Exception as e:  # noqa: BLE001
                print(f"      save-all 异常：{type(e).__name__}")

    # 停掉 30s 健康守卫循环 —— 否则它会把我们刚停掉的服务端顶回来
    ps(cli, "Stop-ScheduledTask -TaskName 'BreakfrontUpdater' -EA SilentlyContinue")
    print("      Stop：BreakfrontUpdater（健康守卫）")

    # 优雅关服（此刻守卫已停，不会有人来抢）
    if java_procs(cli):
        try:
            run_rcon(cli, "say §c[系统] 服务器即将重启，请稍候")
            run_rcon(cli, "stop")
        except Exception as e:  # noqa: BLE001
            print(f"      RCON 发送异常（多半是连接被关，正常）：{type(e).__name__}")
        if not wait_until(lambda: not java_procs(cli), timeout, 5, "java 进程退出"):
            print("      超时未退出 → 强杀")
            ps(cli, "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
                    "Where-Object { $_.CommandLine -like '*fabric-server-launch*' } | "
                    "ForEach-Object { Stop-Process -Id $_.ProcessId -Force }")
            time.sleep(5)
    else:
        print("      没有服务端进程在跑，跳过优雅关服")

    # 收尾：停掉服务端任务本身（若还有残留树会被一并清掉）
    ps(cli, "Stop-ScheduledTask -TaskName 'BreakfrontServer' -EA SilentlyContinue")
    out = ps(cli, "foreach($n in '" + "','".join(KILL_TASKS + KEEP_TASKS) + "'){"
                  "$t=Get-ScheduledTask -TaskName $n -EA SilentlyContinue;"
                  "if($t){ '   ' + $n + '  State=' + $t.State + '  Enabled=' + $t.Settings.Enabled } }")
    print(out.strip())

    wait_until(lambda: not port_pid(cli), 60, 3, f"{PORT} 端口释放")
    return not java_procs(cli)


def panel_start(cli, uuid: str) -> bool:
    print("[3] 面板启动 Breakfront 实例")
    h, b = inst_action(cli, "open", uuid)
    st = h.splitlines()[0] if h else "?"
    print(f"      open -> {st}")
    if " 200" not in st:
        print(f"      响应：{b[:300]}")
        return False
    return True


def panel_status(cli, uuid: str):
    """读实例状态。⚠️ 必须走 inst_list —— 实例运行时 `GET /api/instance`（详情）
    会稳定 500「请求超时！请联系管理员检查节点网络状态」，实测两次都栽在这，
    列表接口则一直正常。"""
    for i in inst_list(cli):
        if i["instanceUuid"] == uuid:
            return i.get("status"), (i.get("info") or {})
    return None, {}


def verify(cli, uuid: str, timeout: int = 300) -> bool:
    print("[4] 验证")
    if not wait_until(lambda: port_pid(cli), timeout, 5, f"{PORT} 开始监听"):
        return False
    if not wait_until(lambda: "Done (" in log_tail(cli, 40), 180, 5, "日志出现 Done ("):
        return False
    wait_until(lambda: rcon_alive(cli), 120, 5, "RCON 响应 list")

    def is_running():
        try:
            return panel_status(cli, uuid)[0] == 3
        except Exception:  # noqa: BLE001
            return False

    # ⚠️ daemon 状态枚举：STOP=0 / STOPPING=1 / STARTING=2 / RUNNING=3（3 才是运行中）
    wait_until(is_running, 120, 5, "面板状态 = 运行中")
    st, info = panel_status(cli, uuid)
    print(f"      面板 status={st}（0=已停止 1=停止中 2=启动中 3=运行中）"
          f"  MC在线={info.get('mcPingOnline')}  → {'✓' if st == 3 else '✗'}")
    print("      日志尾部：")
    for ln in log_tail(cli, 6).strip().splitlines():
        print("        " + ln[:150])
    return st == 3


def rollback(cli, uuid: str = "") -> None:
    print("[!] 回滚：kill MCSM 实例 + 恢复 BreakfrontServer 托管")
    if uuid:
        try:
            inst_action(cli, "kill", uuid)
            print(f"      已 kill {uuid}")
        except Exception as e:  # noqa: BLE001
            print(f"      kill 异常（可忽略）：{e}")
    time.sleep(5)
    ps(cli, "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
            "Where-Object { $_.CommandLine -like '*fabric-server-launch*' } | "
            "ForEach-Object { Stop-Process -Id $_.ProcessId -Force }")
    time.sleep(3)
    ps(cli, "Enable-ScheduledTask -TaskName 'BreakfrontServer' -EA SilentlyContinue | Out-Null; "
            "Start-ScheduledTask -TaskName 'BreakfrontServer' -EA SilentlyContinue; "
            "Enable-ScheduledTask -TaskName 'BreakfrontUpdater' -EA SilentlyContinue | Out-Null; "
            "Start-ScheduledTask -TaskName 'BreakfrontUpdater' -EA SilentlyContinue")
    print("      已重新启用并启动 BreakfrontServer / BreakfrontUpdater")
    wait_until(lambda: port_pid(cli), 240, 5, "服务端恢复监听")


def check(cli) -> None:
    print("== 前置体检 ==")
    st = {0: "已停止", 1: "停止中", 2: "启动中", 3: "运行中"}
    insts = inst_list(cli)
    bf = find_by_nick(insts, NICKNAME)
    if bf:
        print(f"  实例      : {bf['instanceUuid']}  status={bf.get('status')}"
              f"（{st.get(bf.get('status'), '?')}）"
              f"  autoStart={(bf.get('config') or {}).get('eventTask', {}).get('autoStart')}")
    else:
        print("  实例      : （缺）")
    print(f"  java 进程 : {java_procs(cli) or '（无）'}")
    print(f"  25565     : {port_pid(cli) or '（无监听）'}")
    print(f"  RCON      : {rcon_alive(cli) or '（无响应）'}")
    print(f"  开机任务  : {ps(cli, '(Get-ScheduledTask -TaskName ' + BOOT_TASK + ' -EA SilentlyContinue).State').strip() or '（无）'}")


def main() -> int:
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 1
    cli = connect()
    uuid = ""
    try:
        if args[0] == "--check":
            check(cli)
            return 0
        if args[0] == "--rollback":
            bf = find_by_nick(inst_list(cli), NICKNAME)
            rollback(cli, bf["instanceUuid"] if bf else "")
            return 0
        if args[0] == "--fix-boot":
            ensure_boot_task(cli)
            return 0
        if args[0] != "--go":
            print(__doc__)
            return 1

        check(cli)
        print("\n===== 开始接管 =====")
        ensure_boot_task(cli)
        # ⚠️ 改 type/autoStart 必须重建实例：先把实例停掉再重建，否则 daemon 手里的
        #    旧实例对象被抽走，正在跑的服务端会变成孤儿进程、面板失控
        old = find_by_nick(inst_list(cli), NICKNAME)
        if old and old.get("status") != 0:
            print("[0b] 实例在跑，先停掉再改配置")
            inst_action(cli, "stop", old["instanceUuid"])
            wait_until(lambda: not java_procs(cli), 180, 5, "服务端退出")
        uuid = ensure_instance(cli, True)
        if not quiesce(cli):
            raise RuntimeError("无法停掉旧服务端进程")
        if not panel_start(cli, uuid):
            raise RuntimeError("面板启动失败")
        if not verify(cli, uuid):
            raise RuntimeError("启动后验证未通过")
        print("\n===== 接管完成 =====")
        print("  停机窗口约 1–3 分钟；MCSM 已接管启停，热更新由 MCSMBoot/守卫另行处理")
        return 0
    except Exception as e:  # noqa: BLE001
        print(f"\n[✗] 失败：{e}")
        rollback(cli, uuid)
        return 2
    finally:
        cli.close()


if __name__ == "__main__":
    raise SystemExit(main())
