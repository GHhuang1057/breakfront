#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""修复「假玩家壳 BOT」的三个致命缺陷（幂等重放）。

背景（2026-09-10 实测）：/bf bot trial 3 之后服务端出现
    804 次「生成假玩家」/ 817 次 joined the game / 799 次「真人热顶替：移除」
    —— 无限 churn，RCON 直接超时，服务端被拖死。

三个根因：
  1) **递归**：BotPlayerFactory.create 内部走 playerManager.onPlayerConnect →
     触发 Fabric 的 JOIN 事件 → ServerMatch.onPlayerJoin 无条件跑 bots.reconcile()
     → reconcile 又生成 BOT → 又触发 JOIN …… 直到把服务端打爆。
  2) **无重入保护**：即便修了 1)，任何「生成过程中再进 reconcile」的路径都会翻车。
  3) **命名不可靠**：name = "BF_" + A/D + (attSeq + defSeq + troopers.size())，
     三个都在变 → 名字既不单调也可能撞名；撞名等于同一个离线 UUID，
     troopers.put 覆盖旧键 → 名册大小不增长 → reconcile 误判「还缺人」→ 反复生成。

修法：JOIN 里对 BOT 早返回 + reconcile 加重入闸 + 名字改为「最小未占用序号」。

用法：
    python scripts/_patch_botfix.py --check
    python scripts/_patch_botfix.py
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SM = ROOT / "core/src/main/java/com/breakfront/server/ServerMatch.java"
BS = ROOT / "core/src/main/java/com/breakfront/server/BotSquad.java"

EDITS: list[tuple[Path, str, str, str]] = []


def E(f: Path, tag: str, old: str, new: str):
    EDITS.append((f, tag, old, new))


# ==========================================================================
# 1. ServerMatch.onPlayerJoin：BOT 早返回（根因一）
# ==========================================================================
E(
    SM,
    "sm-join-skip-bot",
    """    public void onPlayerJoin(MinecraftServer server, ServerPlayerEntity player) {
        applyCombatModel(player);
        if (teams.sideOf(player.getUuid()) == null) {
            teams.assignLeast(player.getUuid());
        }
        // 真人入编 → **立刻**对账：该侧（真人+BOT）超编时移除一个 BOT，真人直接接管它的名额。
        // 放在部署之前，保证 BOT 让出的位置不会被下一轮补员抢回去。
        bots.reconcile(this, server);""",
    """    public void onPlayerJoin(MinecraftServer server, ServerPlayerEntity player) {
        applyCombatModel(player);
        // ⚠️⚠️ AI 假玩家也是「玩家连接」，会走同一个 JOIN 事件。这里必须**早返回**：
        //   对账会生成新 BOT，新 BOT 又触发 JOIN → 对账 → …… 无限递归。
        //   实测后果：一次 /bf bot trial 3 打出 804 次生成 / 817 次 join / 799 次移除，
        //   服务端 tick 落后 40+，RCON 直接超时。
        //   假玩家的出装/站位由 BotSquad.spawn/applyLoadout 自己负责，无需走真人流程。
        if (BotPlayerFactory.isBot(player)) {
            return;
        }
        if (teams.sideOf(player.getUuid()) == null) {
            teams.assignLeast(player.getUuid());
        }
        // 真人入编 → **立刻**对账：该侧（真人+BOT）超编时移除一个 BOT，真人直接接管它的名额。
        // 放在部署之前，保证 BOT 让出的位置不会被下一轮补员抢回去。
        bots.reconcile(this, server);""",
)

# ==========================================================================
# 2. ServerMatch：删掉已失效的「僵尸壳 NPC 计入圈内人数」扫描
# ==========================================================================
E(
    SM,
    "sm-drop-npc-scan",
    """            // NPC 增援计入圈内人数（zombie + 阵营 tag）
            double r = anchor.radius() + 1;
            for (net.minecraft.entity.LivingEntity le : overworld.getEntitiesByClass(
                    net.minecraft.entity.LivingEntity.class,
                    new net.minecraft.util.math.Box(anchor.x() - r, -64, anchor.z() - r,
                            anchor.x() + r, 320, anchor.z() + r),
                    e -> e.getCommandTags().contains("breakfront.npc"))) {
                if (!anchor.contains(le.getX(), le.getZ())) {
                    continue;
                }
                if (le.getCommandTags().contains("bf.side.att")) {
                    attackers++;
                } else if (le.getCommandTags().contains("bf.side.def")) {
                    defenders++;
                }
            }
            game.applyZonePresence(idx, attackers, defenders, 0.05);""",
    """            // 注：此处原有一段「僵尸壳 NPC 计入圈内人数」的实体扫描（tag=breakfront.npc）。
            // 僵尸壳已整体移除，AI 假玩家本身就是 ServerPlayerEntity，已在上面的玩家循环里
            // 按 sideOfEntity 计入，无需重复扫描 —— 且那段是「每个据点每 tick 一次
            // getEntitiesByClass(AABB)」，属于纯粹的每 tick 浪费。
            game.applyZonePresence(idx, attackers, defenders, 0.05);""",
)

# ==========================================================================
# 3. BotSquad：重入闸 + 字段（根因二）
# ==========================================================================
E(
    BS,
    "bs-reentrancy-field",
    """    /** 累计顶替次数（诊断用）。 */
    private int takeoverCount;""",
    """    /** 累计顶替次数（诊断用）。 */
    private int takeoverCount;
    /**
     * 对账重入闸。生成 BOT 会触发玩家 JOIN 事件，任何「JOIN → reconcile」的接线
     * 都可能形成递归；这里做最后一道保险（第一道在 ServerMatch.onPlayerJoin 的 BOT 早返回）。
     */
    private boolean reconciling;""",
)

E(
    BS,
    "bs-reconcile-guard",
    """    public void reconcile(ServerMatch match, MinecraftServer server) {
        // 1) 清扫阵亡单位：玩家壳死亡后仍会留在玩家列表等待重生，必须显式摘除，""",
    """    public void reconcile(ServerMatch match, MinecraftServer server) {
        if (reconciling) {
            return;                      // 防重入（见 reconciling 字段注释）
        }
        reconciling = true;
        try {
            reconcileLocked(match, server);
        } finally {
            reconciling = false;
        }
    }

    private void reconcileLocked(ServerMatch match, MinecraftServer server) {
        // 1) 清扫阵亡单位：玩家壳死亡后仍会留在玩家列表等待重生，必须显式摘除，""",
)

# ==========================================================================
# 4. BotSquad：命名改为「最小未占用序号」（根因三）
# ==========================================================================
E(
    BS,
    "bs-name-gen",
    """        String cls = Kits.CLASSES[((side == Side.ATTACKER ? attSeq++ : defSeq++)) % Kits.CLASSES.length];
        // 名字须 ≤16 字符且唯一：BF_<A|D><序号>
        String name = NAME_PREFIX + (side == Side.ATTACKER ? "A" : "D")
                + (attSeq + defSeq + troopers.size());""",
    """        String cls = Kits.CLASSES[((side == Side.ATTACKER ? attSeq++ : defSeq++)) % Kits.CLASSES.length];
        String name = nextName(side);""",
)

E(
    BS,
    "bs-nextname",
    """    /** 统一出装：游戏模式、100HP 满血、兵种装备、阵营标签。 */""",
    """    /**
     * 取该侧**最小未被占用**的编制名：{@code BF_A<n>} / {@code BF_D<n>}。
     *
     * <p>必须保证唯一：假玩家的 UUID 由离线玩家名派生（见 BotPlayerFactory），
     * <b>同名 = 同 UUID = 同一个玩家身份</b>。一旦撞名，{@code troopers.put} 会覆盖
     * 旧键 → 名册大小不增长 → 对账误判「还缺人」→ 反复生成，形成 churn。
     *
     * <p>用「最小未占用」而不是自增计数器，是为了让编号稳定收敛在 BF_A1..BF_A<n>
     * （阵亡单位由 {@link #respawn} 同名重建，不会占新号），玩家列表/Tab 更干净。
     * 名字同时是**客户端判定阵营的唯一依据**（见客户端 BotNames.sideOfBotName）。
     */
    private String nextName(Side side) {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (Trooper t : troopers.values()) {
            used.add(t.name);
        }
        String prefix = NAME_PREFIX + (side == Side.ATTACKER ? "A" : "D");
        for (int n = 1; n < 100000; n++) {
            String cand = prefix + n;
            if (!used.contains(cand)) {
                return cand;
            }
        }
        return prefix + "X" + troopers.size();
    }

    /** 统一出装：游戏模式、100HP 满血、兵种装备、阵营标签。 */""",
)


def main() -> int:
    if "--check" in sys.argv:
        bad = 0
        for f, tag, old, _ in EDITS:
            n = f.read_text(encoding="utf-8").count(old)
            if n != 1:
                print(f"  [x] {tag}: 命中 {n} 次（应为 1）")
                bad += 1
        print(f"校验完成：{len(EDITS)} 处，失败 {bad} 处（未写盘）")
        return 1 if bad else 0

    cache: dict[Path, str] = {}
    bad = 0
    for f, tag, old, new in EDITS:
        src = cache.get(f) or f.read_text(encoding="utf-8")
        if src.count(old) != 1:
            print(f"  [x] {tag}: 命中 {src.count(old)} 次，跳过")
            bad += 1
            cache[f] = src
            continue
        cache[f] = src.replace(old, new, 1)
    for f, src in cache.items():
        f.write_text(src, encoding="utf-8")
    print(f"已重放 {len(EDITS) - bad}/{len(EDITS)} 处改动")
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
