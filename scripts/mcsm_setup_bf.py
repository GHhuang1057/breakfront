r"""在 MCSM 面板中创建 / 维护 Breakfront 服务端实例。

背景：MCSM (MCSManager v10) 的实例管理 API 没有官方文档，路由名与直觉不符，
参数该放 query 还是 body 极易踩错。本脚本把**实测结论**固化下来，避免重复试错。

────────────────────── 实测结论（v10.18.3 / daemon 4.18.3）──────────────────────
面板 web = 23333，daemon = 24444（与直觉相反，详见 mcsm_api.py 头注释）。
鉴权 = 面板会话 Cookie + URL `?token=`（见 mcsm_api.panel_call）。

  实例列表  GET    /api/service/remote_service_instances
                   query: daemonId, page(从 1 起), page_size
                   → data.data[]（是分页壳，实例数组在 data.data）
  实例详情  GET    /api/instance
                   query: daemonId, uuid
  实例创建  POST   /api/instance
                   query: daemonId（**必须放 query**，放 body 会 400）
                   body : 完整 InstanceConfig 对象
                   daemon 侧 `instance/new` 处理器把 body 整体当 config 交给
                   createInstance()，缺省字段自动补默认值 → 一次请求配好。
  实例更新  PUT    /api/protected_instance/instance_update
                   query: daemonId, uuid；body: config
                   ⚠️ 实测返回 200 true 但**配置不落盘**（daemon 的
                      instance.parameters() 对该路径未生效）。
                      改配置请走「删除 → 重建」。
  实例删除  DELETE /api/instance
                   query: daemonId
                   body : {"uuids": [uuid, ...], "deleteFile": false}
                   （少了 deleteFile 会报 Validator failed: "deleteFile" is required!）
  启动/停止/重启/强杀 —— 全部是 GET，参数在 query
                   /api/protected_instance/open | stop | restart | kill
                   query: daemonId, uuid

────────────────────────── 路径写法 · 必读 ──────────────────────────
Windows 路径必须写**正斜杠**。MCSM 落盘时会把反斜杠改写：
    `J:\bfserver\server`  →  `J://bfserver//server`
虽然 Windows 容忍双斜杠，但 `J:/bfserver/server` 能原样落盘、更干净，
且实测正斜杠 exe 路径可直接执行（`& 'J:/bfserver/jdk21/bin/java.exe' -version` 正常）。

──────────────────────────── 用法 ────────────────────────────
  python scripts/mcsm_setup_bf.py list             # 列出该 daemon 下所有实例
  python scripts/mcsm_setup_bf.py show <uuid>      # 查看某实例完整配置
  python scripts/mcsm_setup_bf.py create           # 创建 Breakfront 实例（幂等）
  python scripts/mcsm_setup_bf.py create --autostart
  python scripts/mcsm_setup_bf.py delete <uuid> [--purge]   # --purge 同时删实例目录
  python scripts/mcsm_setup_bf.py cleanup          # 删除所有非 Breakfront 的测试实例
  python scripts/mcsm_setup_bf.py open|stop|restart|kill <uuid>

⚠️ 接管提醒：Breakfront 服务端目前由自研计划任务 BreakfrontServer（跑 server\start.bat）
   与 BreakfrontUpdater（30s 健康守卫：进程或 25565 监听任一即健康）共同托管。
   在 MCSM 里启动之前，必须先停用这两个任务，否则两边抢启停。
   本脚本默认只**创建**、不启动（--autostart 也不启动，只改自启标志）。
"""
from __future__ import annotations

import json
import sys

sys.path.insert(0, "scripts")
from mc_remote import connect  # noqa: E402
import mcsm_api as M  # noqa: E402

# 远程节点（daemon）ID —— 来自 web/data/RemoteServiceConfig/<id>.json 的文件名
DAEMON_ID = "f9d6aae8af834182b85d279f9ac2cbd7"
NICKNAME = "Breakfront"

# ⚠️ 全部用正斜杠（见文件头「路径写法」）
BF_CONFIG = {
    "nickname": NICKNAME,
    "startCommand": ("J:/bfserver/jdk21/bin/java.exe -Xmx32G -Xms2G "
                     "-jar fabric-server-launch.jar nogui"),
    "stopCommand": "stop",          # 写进 stdin，MC 服务端读 stdin 执行
    "cwd": "J:/bfserver/server",
    # ⚠️ 必须用 minecraft/java（不是 universal）：daemon 的 FunctionDispatcher 里
    #    只有 type 含 TYPE_MINECRAFT_JAVA 才注册 MC ping 生命周期任务与玩家追踪，
    #    否则面板会永远显示「MC 离线」，且没有玩家列表。
    "type": "minecraft/java",
    "ie": "utf8",
    "oe": "utf8",
    "fileCode": "utf8",
    "processType": "general",
    "crlf": 2,                      # win32 行尾，stop 命令按 \r\n 发送
    "stopTimeout": 0,
    "basePort": 0,
    "updateCommand": "",
    "runAs": "",
    "tag": ["breakfront", "mc"],
    "actionCommandList": [],
    "terminalOption": {
        "haveColor": True,          # 保留 ANSI 颜色，控制台日志好读
        "pty": True,
        "ptyWindowCol": 200,
        "ptyWindowRow": 50,
    },
    "pingConfig": {"ip": "127.0.0.1", "port": 25565, "type": 1},
    "eventTask": {
        "autoStart": False,         # 迁移完成、旧守卫停用后再打开
        "autoRestart": True,        # 崩溃自动重启（接管后由 MCSM 兜底）
        "autoRestartMaxTimes": -1,  # -1 = 不限次数
        "ignore": False,
    },
    "java": {"id": ""},
}


# ────────────────────────── 面板 API 薄封装 ──────────────────────────

def inst_list(cli) -> list[dict]:
    """列出该 daemon 下的全部实例（自动翻页）。"""
    out, page = [], 1
    while True:
        h, b = M.panel_call(cli, "GET",
                            f"/api/service/remote_service_instances"
                            f"?daemonId={DAEMON_ID}&page={page}&page_size=100")
        j = json.loads(b)
        if not isinstance(j.get("data"), dict):
            raise RuntimeError(f"列表接口返回异常：{b[:300]}")
        rows = j["data"].get("data", [])
        out += rows
        total = j["data"].get("total") or len(out)
        if not rows or len(out) >= total:
            return out
        page += 1


def inst_detail(cli, uuid: str) -> dict:
    h, b = M.panel_call(cli, "GET", f"/api/instance?daemonId={DAEMON_ID}&uuid={uuid}")
    j = json.loads(b)
    if j.get("status") != 200:
        raise RuntimeError(f"读取实例失败：{b[:300]}")
    return j["data"]


def inst_create(cli, config: dict) -> dict:
    """POST /api/instance?daemonId=...  body = 完整配置（见文件头）。"""
    h, b = M.panel_call(cli, "POST", f"/api/instance?daemonId={DAEMON_ID}", config)
    j = json.loads(b)
    if j.get("status") != 200:
        raise RuntimeError(f"创建实例失败：{b[:300]}")
    return j["data"]


def inst_delete(cli, uuids: list[str], purge: bool = False) -> dict:
    h, b = M.panel_call(cli, "DELETE", f"/api/instance?daemonId={DAEMON_ID}",
                        {"uuids": uuids, "deleteFile": purge})
    j = json.loads(b)
    if j.get("status") != 200:
        raise RuntimeError(f"删除实例失败：{b[:300]}")
    return j["data"]


def inst_action(cli, action: str, uuid: str) -> tuple[str, str]:
    """action ∈ open | stop | restart | kill（GET，参数在 query）。"""
    assert action in ("open", "stop", "restart", "kill"), action
    return M.panel_call(cli, "GET",
                        f"/api/protected_instance/{action}"
                        f"?daemonId={DAEMON_ID}&uuid={uuid}")


def find_by_nick(insts: list[dict], nick: str) -> dict | None:
    for i in insts:
        if (i.get("config") or {}).get("nickname") == nick:
            return i
    return None


# ────────────────────────────── 子命令 ──────────────────────────────

def cmd_list(cli) -> int:
    insts = inst_list(cli)
    if not insts:
        print("(该节点下没有实例)")
        return 0
    print(f"{'UUID':<34} {'昵称':<16} {'状态':<8} {'MC在线':<7} cwd")
    print("-" * 118)
    for i in insts:
        c = i.get("config") or {}
        info = i.get("info") or {}
        # ⚠️ daemon 的实例状态枚举：STOP=0 / STOPPING=1 / STARTING=2 / RUNNING=3（不是 1=运行中！）
        state = {0: "已停止", 1: "停止中", 2: "启动中", 3: "运行中"}.get(i.get("status"), "?")
        mc = "✓" if info.get("mcPingOnline") else "-"
        print(f"{i['instanceUuid']:<34} {str(c.get('nickname'))[:15]:<16} {state:<8} "
              f"{mc:<7} {c.get('cwd')}")
    return 0


def cmd_show(cli, uuid: str) -> int:
    d = inst_detail(cli, uuid)
    print(json.dumps(d, ensure_ascii=False, indent=2))
    return 0


def cmd_create(cli, autostart: bool) -> int:
    cfg = json.loads(json.dumps(BF_CONFIG))          # 深拷贝
    if autostart:
        cfg["eventTask"]["autoStart"] = True
    exist = find_by_nick(inst_list(cli), NICKNAME)
    if exist:
        old = exist["instanceUuid"]
        print(f"[*] 已存在同名实例 {old}，按「删除→重建」刷新配置")
        inst_delete(cli, [old], purge=False)
    d = inst_create(cli, cfg)
    uuid = d["instanceUuid"]
    got = d.get("config") or {}
    print(f"[✓] 创建成功 uuid={uuid}")
    print(f"    昵称  : {got.get('nickname')}")
    print(f"    cwd   : {got.get('cwd')}")
    print(f"    启动  : {got.get('startCommand')}")
    print(f"    停止  : {got.get('stopCommand')!r}")
    print(f"    自启  : {got.get('eventTask', {}).get('autoStart')}"
          f" / 崩自愈: {got.get('eventTask', {}).get('autoRestart')}")
    print("\n"+ "=" * 74)
    print("⚠️  接管前必做：停用自研计划任务 BreakfrontServer 与 BreakfrontUpdater，")
    print("    否则会和 MCSM 抢启停（Updater 的 30s 守卫会把 MCSM 停掉的服务重新拉起）。")
    print("    BreakfrontFrpc 保留（公网入口靠它）。")
    print("    Stop-ScheduledTask -TaskName BreakfrontServer,BreakfrontUpdater")
    print("=" * 74)
    return 0


def cmd_delete(cli, uuid: str, purge: bool) -> int:
    inst_delete(cli, [uuid], purge=purge)
    print(f"[✓] 已删除 {uuid}（实例目录{'一并删除' if purge else '保留'}）")
    return 0


def cmd_cleanup(cli) -> int:
    """删除除 Breakfront 之外的所有实例（清掉历史探测残留）。"""
    insts = inst_list(cli)
    victims = [i["instanceUuid"] for i in insts
               if (i.get("config") or {}).get("nickname") != NICKNAME]
    if not victims:
        print("[*] 没有需要清理的实例")
        return 0
    for v in victims:
        inst_delete(cli, [v], purge=False)
        print(f"[✓] 已删除 {v}")
    return 0


def cmd_action(cli, action: str, uuid: str) -> int:
    h, b = inst_action(cli, action, uuid)
    print(h.splitlines()[0] if h else "?")
    print(b[:400])
    return 0


def main() -> int:
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 1
    cmd, rest = args[0], args[1:]
    autostart = "--autostart" in rest
    purge = "--purge" in rest
    rest = [a for a in rest if not a.startswith("--")]

    cli = connect()
    try:
        if cmd == "list":
            return cmd_list(cli)
        if cmd == "show":
            return cmd_show(cli, rest[0])
        if cmd == "create":
            return cmd_create(cli, autostart)
        if cmd == "delete":
            return cmd_delete(cli, rest[0], purge)
        if cmd == "cleanup":
            return cmd_cleanup(cli)
        if cmd in ("open", "stop", "restart", "kill"):
            return cmd_action(cli, cmd, rest[0])
        print(f"未知子命令：{cmd}")
        print(__doc__)
        return 1
    finally:
        cli.close()


if __name__ == "__main__":
    raise SystemExit(main())
