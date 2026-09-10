#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""重放「假玩家壳 BOT」相关的阵营判定接线（幂等）。

背景：BOT 已从 ZombieEntity 壳换成 ServerPlayerEntity 壳（BotPlayerFactory +
BotSquad）。客户端原来的「AI 增援靠名字前缀『攻方增援』判定」彻底失效 ——
新壳的玩家名字是 BF_A<序号>/BF_D<序号>，且原版会把它当真人。

本脚本做两件事：
  1. 服务端位置帧（PlayerPosPayload，0.25s）改用 sideOfEntity —— 让 AI 假玩家
     也带上真实阵营（它们不在 TeamManager 里，原来一律 -1）。位置帧是全量名册，
     客户端据此可查任意在线单位的阵营（含大厅无击杀阶段）。
  2. 客户端 FriendlyHostileMarks 改为：只扫玩家实体、阵营查 friends() 名册、
     兜底用 BF_A/BF_D 前缀。

用法：
    python scripts/_patch_botside.py --check
    python scripts/_patch_botside.py
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SM = ROOT / "core/src/main/java/com/breakfront/server/ServerMatch.java"
FM = ROOT / "client/src/main/java/com/breakfront/client/hud/FriendlyHostileMarks.java"

EDITS: list[tuple[Path, str, str, str]] = []


def E(f: Path, tag: str, old: str, new: str):
    EDITS.append((f, tag, old, new))


# ==========================================================================
# 1. 服务端：位置帧带 AI 假玩家的阵营
# ==========================================================================
E(
    SM,
    "sm-pos-side",
    """        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            Side side = teams.sideOf(p.getUuid());
            int s = side == null ? -1 : side.ordinal();""",
    """        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            // ⚠️ 必须走 sideOfEntity 而非 teams.sideOf：AI 假玩家（BotSquad）不在
            // TeamManager 里，只挂了 bf.side.att/def 命令标签；用 teams.sideOf 会让它们
            // 一律变成 -1，客户端据此就无法给 BOT 标友方/敌方（雷达、头顶菱形全会缺）。
            Side side = sideOfEntity(p);
            int s = side == null ? -1 : side.ordinal();""",
)

# ==========================================================================
# 2. 客户端：标记层阵营判定改为「全量名册 + 名字前缀兜底」
# ==========================================================================
E(
    FM,
    "fm-imports",
    """import com.breakfront.client.state.ClientMatchState;
import com.breakfront.client.state.ClientMatchState.BoardRow;""",
    """import com.breakfront.client.state.ClientMatchState;
import com.breakfront.client.state.ClientMatchState.BoardRow;
import com.breakfront.client.state.ClientMatchState.FriendDot;""",
)

E(
    FM,
    "fm-drop-zombie-import",
    """import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.player.PlayerEntity;""",
    """import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;""",
)

E(
    FM,
    "fm-javadoc",
    """ * 阵营判定（无新增协议）：
 *   - 自身/真人玩家：ClientMatchState.board()（服务端 1s 一帧，含 sideOrdinal）按名字匹配；
 *   - AI 增援：NpcSquad 生成名字前缀「攻方增援/守方增援」（服务端已隐藏原版名字牌，
 *     本层以菱形代替标签）。""",
    """ * 阵营判定（无新增协议）：
 *   - 主来源 {@link ClientMatchState#friends()}：PlayerPosPayload 每 0.25s 刷新，
 *     含**全部在线单位（真人 + AI 假玩家）**的 name→side —— 大厅阶段（无击杀、
 *     战绩榜为空）同样可靠；
 *   - 次来源 ClientMatchState.board()：只登记参与过击杀的人，作兜底；
 *   - 末位兜底：AI 假玩家命名 BF_A<序号>/BF_D<序号>（见 BotSquad#spawn），
 *     按前缀即时判别（刚进服、位置帧尚未到达的头 0.25s 也不会漏标）。""",
)

E(
    FM,
    "fm-scan",
    """        var actors = world.getEntitiesByClass(Entity.class, scanBox,
                e -> e instanceof PlayerEntity || e instanceof ZombieEntity);""",
    """        // AI 假玩家已是**真实 ServerPlayerEntity 壳**（BotSquad），故只需扫玩家实体
        var actors = world.getEntitiesByClass(Entity.class, scanBox,
                e -> e instanceof PlayerEntity);""",
)

E(
    FM,
    "fm-drawpass",
    """            boolean isBot = e instanceof ZombieEntity;
            boolean isPlayer = e instanceof PlayerEntity;
            if (!isBot && !isPlayer) {
                continue;
            }
            int side = isBot
                    ? botSide((ZombieEntity) e)
                    : playerSide(e.getDisplayName() != null ? e.getDisplayName().getString() : "");
            if (side < 0) {
                continue; // 未分配/查不到阵营：不标
            }""",
    """            if (!(e instanceof PlayerEntity pe)) {
                continue;
            }
            // 用 GameProfile 名（稳定、与位置帧/战绩榜同名）而非展示名（可能带队伍前缀）
            int side = sideOfName(pe.getGameProfile().getName());
            if (side < 0) {
                continue; // 未分配/查不到阵营：不标
            }""",
)

E(
    FM,
    "fm-sidemethods",
    """    /** 自身 side（board 内按名字匹配）；未入队返回 -1。 */
    private static int selfSide() {
        String me = MinecraftClient.getInstance().player.getName().getString();
        for (BoardRow r : ClientMatchState.board()) {
            if (r.name().equals(me)) {
                return r.sideOrdinal();
            }
        }
        return -1;
    }

    /** 真人玩家 side：board 匹配名字；查不到返回 -1。 */
    private static int playerSide(String name) {
        if (name == null || name.isEmpty()) {
            return -1;
        }
        for (BoardRow r : ClientMatchState.board()) {
            if (r.name().equals(name)) {
                return r.sideOrdinal();
            }
        }
        return -1;
    }

    /** bot side：NpcSquad 生成名前缀（攻方增援/守方增援）。 */
    private static int botSide(ZombieEntity e) {
        var cn = e.getCustomName();
        if (cn == null) {
            return -1;
        }
        String s = cn.getString();
        if (s.startsWith("攻方增援")) {
            return 0;
        }
        if (s.startsWith("守方增援")) {
            return 1;
        }
        return -1;
    }
}""",
    """    /** 自身 side；未入队返回 -1。 */
    private static int selfSide() {
        return sideOfName(MinecraftClient.getInstance().player.getGameProfile().getName());
    }

    /**
     * 名字 → 阵营序号（0=攻 / 1=守 / -1=未知）。
     *
     * <p>优先级：位置帧名册（全量、0.25s 刷新） → 战绩榜（仅参与过击杀者） →
     * AI 命名前缀兜底。**不能只用战绩榜**：大厅阶段没有击杀，榜是空的，
     * 那样会导致自身 side 都算不出来 → 整个标记层直接不渲染。
     */
    private static int sideOfName(String name) {
        if (name == null || name.isEmpty()) {
            return -1;
        }
        for (FriendDot f : ClientMatchState.friends()) {
            if (f.name().equals(name) && f.sideOrdinal() >= 0) {
                return f.sideOrdinal();
            }
        }
        for (BoardRow r : ClientMatchState.board()) {
            if (r.name().equals(name)) {
                return r.sideOrdinal();
            }
        }
        if (name.startsWith("BF_A")) {
            return 0;
        }
        if (name.startsWith("BF_D")) {
            return 1;
        }
        return -1;
    }
}""",
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
