#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一次性重放 core 侧的改动（任务：BOT 热顶替 + 扇区地面高度 + squaremap 代理）。

为什么要用脚本重放：本次会话出现过「工作区 core/ 整目录丢失并需 git 恢复」的事故，
逐条 Edit 的改动会一起蒸发。把所有替换收敛成一个幂等脚本，恢复后可一条命令重放。

用法：
    python scripts/_patch_core.py --check     # 只校验能否命中，不写盘
    python scripts/_patch_core.py             # 应用
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SM = ROOT / "core/src/main/java/com/breakfront/server/ServerMatch.java"
BS = ROOT / "core/src/main/java/com/breakfront/server/BotSquad.java"
BC = ROOT / "core/src/main/java/com/breakfront/server/BreakfrontCommands.java"
WA = ROOT / "core/src/main/java/com/breakfront/server/WebAdminConsole.java"

# (文件, 标识, 旧串, 新串)
EDITS: list[tuple[Path, str, str, str]] = []


def E(f: Path, tag: str, old: str, new: str):
    EDITS.append((f, tag, old, new))


# ==========================================================================
# A. ServerMatch —— BOT 热顶替接线
# ==========================================================================
E(
    SM,
    "sm-bots-field",
    """    private final NpcSquad npc = new NpcSquad();
    /** 假玩家小队（ServerPlayerEntity 作壳；与 npc 互斥启用，见 BotSquad 类注释）。 */
    private final BotSquad bots = new BotSquad();""",
    """    /**
     * AI 战场小队（假玩家壳）。
     *
     * <p>2026-09-10 v2：旧的僵尸壳 {@code NpcSquad} 已**整体移除**（它按
     * 「目标 - 在线」补员，无真人时会无限补员 —— 实测 alive 涨到 755，直接把 TPS 拖垮）。
     * 现全部走本小队：大厅维持「非战斗 BOT」编制，真人进服即时热顶替。
     */
    private final BotSquad bots = new BotSquad();""",
)

E(
    SM,
    "sm-layout-apply",
    """        autoArmed = false;
        autoTimer = -1;
        npc.clearAll(server);""",
    """        autoArmed = false;
        autoTimer = -1;
        bots.clearAll(server);""",
)

E(
    SM,
    "sm-tick-npc",
    """        npc.tick(this, server); // NPC 增援向目标点推进
        var overworld = server.getOverworld();""",
    """        var overworld = server.getOverworld();""",
)

E(
    SM,
    "sm-zone-presence",
    """                Side side = teams.sideOf(player.getUuid());
                if (side == null) {
                    continue;
                }
                if (anchor.contains(player.getX(), player.getZ())) {""",
    """                Side side = sideOfEntity(player);
                if (side == null) {
                    continue;
                }
                if (anchor.contains(player.getX(), player.getZ())) {""",
)

E(
    SM,
    "sm-sideofentity",
    """    /**
     * 开局赛程：""",
    """    /**
     * 实体的阵营归属。
     *
     * <p>优先走 {@link TeamManager}；**AI 假玩家不在 TeamManager 里**（它们只是占位编制），
     * 因此回退到命令标签 {@code bf.side.att/def} —— 否则假玩家会被判为「无阵营」，
     * 既不参与据点人数判定，也不吃友伤豁免。
     */
    private Side sideOfEntity(ServerPlayerEntity p) {
        Side s = teams.sideOf(p.getUuid());
        if (s != null) {
            return s;
        }
        if (p.getCommandTags().contains("bf.side.att")) {
            return Side.ATTACKER;
        }
        if (p.getCommandTags().contains("bf.side.def")) {
            return Side.DEFENDER;
        }
        return null;
    }

    /**
     * 开局赛程：""",
)

E(
    SM,
    "sm-fill-arm",
    """                desiredPerSide = pickFillTarget(server);
                npc.applyFill(this, server, desiredPerSide, desiredPerSide);
                autoArmed = true;""",
    """                desiredPerSide = pickFillTarget(server);
                applyFillTarget(server, desiredPerSide);
                autoArmed = true;""",
)

E(
    SM,
    "sm-fill-adapt",
    """                        desiredPerSide = next;
                        npc.applyFill(this, server, desiredPerSide, desiredPerSide);""",
    """                        desiredPerSide = next;
                        applyFillTarget(server, desiredPerSide);""",
)

E(
    SM,
    "sm-beginround",
    """    /** 开局统一入口：重开状态机、清战绩、补 NPC、复位据点标识、全员发装备。 */
    public void beginRound() {
        game.startRound();
        score.reset();
        visualsPlaced = false;
        npc.beginRound(this, BreakfrontServer.server());
        MinecraftServer server = BreakfrontServer.server();
        if (server != null) {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                kitPlayer(server, p);
            }
        }
    }""",
    """    /** 开局统一入口：重开状态机、清战绩、重置 AI 编制、复位据点标识、全员发装备。 */
    public void beginRound() {
        game.startRound();
        score.reset();
        visualsPlaced = false;
        MinecraftServer server = BreakfrontServer.server();
        bots.clearAll(server);          // 新回合：AI 编制归零，随后按对账在新出生点重建
        if (server != null) {
            bots.reconcile(this, server);
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                kitPlayer(server, p);
            }
        }
    }

    /**
     * 设置双阵营填充目标并立即对账（大厅「非战斗 BOT」+ 真人热顶替都在 {@code reconcile} 内完成）。
     */
    private void applyFillTarget(MinecraftServer server, int perSide) {
        bots.setTarget(Side.ATTACKER, perSide);
        bots.setTarget(Side.DEFENDER, perSide);
        bots.reconcile(this, server);
    }""",
)

E(
    SM,
    "sm-npc-getter",
    """    public NpcSquad npc() {
        return npc;
    }

    /** 假玩家小队（AI BOT 重构的玩家壳实现）。 */
    public BotSquad bots() {
        return bots;
    }""",
    """    /** 假玩家小队（AI BOT 的玩家壳实现；大厅「非战斗 BOT」与真人热顶替都在这里）。 */
    public BotSquad bots() {
        return bots;
    }""",
)

E(
    SM,
    "sm-setautofill",
    """        if (!on) {
            npc.clearAll(BreakfrontServer.server());
        }""",
    """        if (!on) {
            bots.clearAll(BreakfrontServer.server());
        }""",
)

E(
    SM,
    "sm-join-reconcile",
    """        if (teams.sideOf(player.getUuid()) == null) {
            teams.assignLeast(player.getUuid());
        }
        // 任意阶段进服都部署到己方出生区（spawnFor 保证落真实实体表面；不再有假高度二次传送）""",
    """        if (teams.sideOf(player.getUuid()) == null) {
            teams.assignLeast(player.getUuid());
        }
        // 真人入编 → **立刻**对账：该侧（真人+BOT）超编时移除一个 BOT，真人直接接管它的名额。
        // 放在部署之前，保证 BOT 让出的位置不会被下一轮补员抢回去。
        bots.reconcile(this, server);
        // 任意阶段进服都部署到己方出生区（spawnFor 保证落真实实体表面；不再有假高度二次传送）""",
)

# ==========================================================================
# B. ServerMatch —— 扇区地面高度（NaN 语义 + 区块按需载入）
# ==========================================================================
E(
    SM,
    "sm-groundy",
    """    private double groundY(ServerWorld world, double x, double z) {
        int topY = world.getTopY(Heightmap.Type.WORLD_SURFACE, (int) Math.floor(x), (int) Math.floor(z));
        if (topY > world.getBottomY()) {
            return topY + 1.0;
        }
        // 该列无方块（低海拔/虚空区）：在 ±6 邻域找最近真实站面，避免回退 64 高空假高度
        double nt = neighborTopY(world, x, z);
        return Double.isNaN(nt) ? world.getBottomY() + 3.0 : nt + 1.0;
    }""",
    """    /**
     * 据点/落点地表高度（最高方块顶 +1）；**无有效地面时返回 {@link Double#NaN}**。
     *
     * <p>⚠️ 2026-09-10 修「扇区区域在游戏中的展示位置/高度错乱」：
     * 原实现在**区块未载入**时 {@code getTopY} 恒返回世界底部，于是退化成
     * {@code bottomY + 3}（≈ -61）这个「假高度」。该值随状态包下发给客户端后，
     * 客户端的据点地面标识（WorldZoneRings 的方坪/边带/角柱/光柱）就画在基岩层以下
     * —— 玩家在 y≈70 时要么完全看不到，要么看到贴地错位/穿模的残影。
     *
     * <p>现改为两级处理：
     * <ol>
     *   <li>先按需载入该列所在**已存在**的区块（{@code create=false}，绝不触发新地形生成）；</li>
     *   <li>仍无地形（坐标真的落在未生成区/虚空）→ 返回 {@code NaN}。
     *       调用方与客户端据此判为「无有效地面」并**跳过渲染**，而不是画一个假高度。</li>
     * </ol>
     */
    private double groundY(ServerWorld world, double x, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
            try {
                world.getChunkManager().getChunk(bx >> 4, bz >> 4,
                        net.minecraft.world.chunk.ChunkStatus.FULL, false);
            } catch (Throwable ignored) {
                // 读盘失败按「无地面」处理
            }
        }
        int topY = world.getTopY(Heightmap.Type.WORLD_SURFACE, bx, bz);
        if (topY > world.getBottomY()) {
            return topY + 1.0;
        }
        // 该列无方块（低海拔/虚空区）：在 ±6 邻域找最近真实站面
        double nt = neighborTopY(world, x, z);
        return Double.isNaN(nt) ? Double.NaN : nt + 1.0;
    }""",
)

E(
    SM,
    "sm-admingoto",
    """        ServerWorld world = server.getOverworld();
        double y = groundY(world, a.x(), a.z()) + 1.5;
        exec(server, String.format("tp %s %.1f %.1f %.1f",
                player.getGameProfile().getName(), a.x(), y, a.z()));""",
    """        ServerWorld world = server.getOverworld();
        double gy = groundY(world, a.x(), a.z());
        if (Double.isNaN(gy)) {
            return String.format("据点 %s 坐标 (x=%.0f, z=%.0f) 处无地形（落在未生成区块），"
                    + "请先在管理台按真实俯瞰图重划扇区", zoneId, a.x(), a.z());
        }
        double y = gy + 1.5;
        exec(server, String.format("tp %s %.1f %.1f %.1f",
                player.getGameProfile().getName(), a.x(), y, a.z()));""",
)

E(
    SM,
    "sm-anchortext",
    """    /** 据点锚点坐标摘要（供 /bf status 展示，方便传送验证）。 */
    public String zoneAnchorsText() {
        StringBuilder sb = new StringBuilder();
        int gi = 0;
        for (String id : zoneOrder) {
            ZoneAnchor a = anchors.get(id);
            char letter = (gi >= 0 && gi < 26) ? (char) ('A' + gi) : '?';
            sb.append('\\n').append(letter).append(" (").append(a.zoneId()).append(") @ (x=")
                    .append(String.format("%.1f", a.x())).append(", z=")
                    .append(String.format("%.1f", a.z())).append(", r=")
                    .append(String.format("%.0f", a.radius())).append(')');
            gi++;
        }
        return sb.toString();
    }""",
    """    /** 据点锚点坐标摘要（供 /bf status 展示，方便传送验证）；无地形的锚点会标红提示。 */
    public String zoneAnchorsText() {
        StringBuilder sb = new StringBuilder();
        var srv = BreakfrontServer.server();
        ServerWorld world = srv == null ? null : srv.getOverworld();
        int gi = 0;
        for (String id : zoneOrder) {
            ZoneAnchor a = anchors.get(id);
            char letter = (gi >= 0 && gi < 26) ? (char) ('A' + gi) : '?';
            sb.append('\\n').append(letter).append(" (").append(a.zoneId()).append(") @ (x=")
                    .append(String.format("%.1f", a.x())).append(", z=")
                    .append(String.format("%.1f", a.z())).append(", r=")
                    .append(String.format("%.0f", a.radius())).append(')');
            // 坐标合法性体检：落在未生成区块的锚点会让地面标识「高度错乱/看不到」
            if (world != null && Double.isNaN(groundY(world, a.x(), a.z()))) {
                sb.append("  §c⚠ 无地形（落在未生成区块，请在管理台按真实俯瞰图重划扇区）§r");
            }
            gi++;
        }
        return sb.toString();
    }""",
)

E(
    SM,
    "sm-placevisuals",
    """    private void placeZoneVisuals(ServerWorld world) {
        for (ZoneAnchor anchor : anchors.values()) {
            int cx = (int) anchor.x();
            int cz = (int) anchor.z();
            int topY = world.getTopY(Heightmap.Type.WORLD_SURFACE, cx, cz);
            if (topY <= world.getBottomY()) {
                continue;
            }
            world.setBlockState(new BlockPos(cx, topY + 1, cz), Blocks.BEACON.getDefaultState(), 3);
        }
    }""",
    """    private void placeZoneVisuals(ServerWorld world) {
        for (ZoneAnchor anchor : anchors.values()) {
            // groundY 内部会按需载入已存在区块；无地形（未生成区）返回 NaN → 跳过，
            // 不再把信标放到世界底部（那会让客户端地面标识整体错位）。
            double gy = groundY(world, anchor.x(), anchor.z());
            if (Double.isNaN(gy)) {
                BreakfrontServer.LOGGER.warn(
                        "[Breakfront] 据点 {} 处无地形，跳过信标放置（坐标落在未生成区块）",
                        anchor.zoneId());
                continue;
            }
            world.setBlockState(new BlockPos((int) Math.floor(anchor.x()), (int) gy,
                    (int) Math.floor(anchor.z())), Blocks.BEACON.getDefaultState(), 3);
        }
    }""",
)

E(
    SM,
    "sm-spawnsfor-doc",
    """    /** 供 NpcSquad 使用的公开坐标（出生 y 含地面）。 */""",
    """    /** 出生点公开坐标（出生 y 已含地面）；供 AI 小队与外部工具复用。 */""",
)

# ==========================================================================
# C. BotSquad —— 编制对账 / 非战斗 BOT / 真人热顶替
# ==========================================================================
E(
    BS,
    "bs-javadoc",
    """ * <h2>与 {@link NpcSquad} 的关系</h2>
 * 并存而非替换。{@code NpcSquad} 用 {@code ZombieEntity} 作壳、原版导航移动；
 * 本类用 {@link ServerPlayerEntity} 假玩家作壳（见 {@link BotPlayerFactory}）、
 * {@link BotMotor} 驱动位移。两者**互斥启用**：
 * <ul>
 *   <li>旧壳渲染/追踪走怪物通路，需不断打补丁（防火、禁 AI、persistent 防 despawn），
 *       仍会有「忽隐忽现」的观感问题；</li>
 *   <li>新壳与真人走**完全相同**的实体、渲染、追踪、伤害、计分通路 —— 从根上消除该问题。</li>
 * </ul>
 * 保留旧实现是为了回退安全：新系统经真机验证后再考虑摘除旧代码。
 *
 * <h2>行为（第一版：跑通优先）</h2>
 * <ul>
 *   <li><b>无敌人</b>：朝当前扇区目标据点推进（攻方聚首点、守方按 id 分散到各点）</li>
 *   <li><b>有敌人</b>（≤{@value #ENGAGE_RANGE}m 且视线通畅）：停下、转向、周期开火</li>
 *   <li><b>阵亡</b>：从名册移除，由补员逻辑在出生点重建（与真人复活语义一致）</li>
 * </ul>
 *
 * <h2>已知取舍</h2>
 * <ul>
 *   <li>假玩家入列会触发服务端「XX joined the game」广播（{@code onPlayerConnect} 内置行为）。
 *       这是管理员指令触发的运维动作，暂接受；若需静默需 mixin 拦截广播。</li>
 *   <li>寻路为「朝目标直线 + 卡住重试」，未做完整 A*。城市街区尚可，复杂室内后续升级。</li>
 * </ul>""",
    """ * <h2>编制模型（2026-09-10 v2：非战斗 BOT + 真人热顶替）</h2>
 * 不变量：**某侧（真人 + BOT）总数 == 该侧目标人数**（见 {@link #reconcile}）。
 * <ul>
 *   <li><b>非战斗 BOT</b>：大厅阶段同样维持编制 —— 空服也始终有成建制的双方；</li>
 *   <li><b>热顶替</b>：真人进服并被分到某侧后，该侧立刻移除一个 BOT，真人直接占位，
 *       <u>不需要</u>改目标人数、也不需要重开回合；真人退服则下一轮对账补回 BOT。</li>
 * </ul>
 *
 * <h2>壳的选择（历史决策）</h2>
 * 早先用 {@code ZombieEntity} 作壳（原 {@code NpcSquad}，已在本版**移除**）：怪物通路与
 * 真人不同，需要不断打补丁（防火、禁 AI、persistent 防 despawn），仍有「忽隐忽现」观感，
 * 且其按「目标 - 在线」补员的写法会在没人时**无限补员**（实测 alive 涨到 755）。
 * 现统一用 {@link ServerPlayerEntity} 假玩家作壳（见 {@link BotPlayerFactory}）：
 * 与真人走**完全相同**的实体、渲染、追踪、伤害、计分通路。
 *
 * <h2>行为</h2>
 * <ul>
 *   <li><b>无敌人</b>：朝当前扇区目标据点推进（攻方聚首点、守方按 id 分散到各点）</li>
 *   <li><b>有敌人</b>（≤{@value #ENGAGE_RANGE}m 且视线通畅）：停下、转向、周期开火</li>
 *   <li><b>阵亡</b>：摘除实体后**同名重建**（身份稳定，不刷玩家列表）</li>
 * </ul>
 *
 * <h2>已知取舍</h2>
 * <ul>
 *   <li>假玩家入列会触发服务端「XX joined the game」广播（{@code onPlayerConnect} 内置行为）。</li>
 *   <li>寻路为「朝目标直线 + 卡住重试」，未做完整 A*。城市街区尚可，复杂室内后续升级。</li>
 * </ul>""",
)

E(
    BS,
    "bs-gunrange-doc",
    """    /** 有效射程：超出不自伤开火（与 NpcSquad 口径一致）。 */""",
    """    /** 有效射程：超出不开火（避免"隔街互射"的观感）。 */""",
)

E(
    BS,
    "bs-fields",
    """    private final Map<UUID, Trooper> troopers = new HashMap<>();
    private int targetAttacker;
    private int targetDefender;
    private int attSeq;
    private int defSeq;
    private int tickCounter;""",
    """    private final Map<UUID, Trooper> troopers = new java.util.LinkedHashMap<>();
    private int targetAttacker;
    private int targetDefender;
    /**
     * 是否在大厅（非战斗）阶段也维持编制 —— 即「非战斗 BOT」。
     *
     * <p>开：一进服就能看到成建制的双方 AI（不再是空荡荡的大厅），
     * 且真人进服时立刻腾出名额。关：只在 BATTLE 阶段填充。
     */
    private boolean lobbyPresence = true;
    /** 最近一次「真人热顶替 BOT」的可读说明（供管理台/日志）。 */
    private volatile String lastTakeover = "";
    /** 累计顶替次数（诊断用）。 */
    private int takeoverCount;
    private int attSeq;
    private int defSeq;
    private int tickCounter;""",
)

E(
    BS,
    "bs-api",
    """    // ---------- 对外接口（与 NpcSquad 对齐，便于将来互换） ----------

    public void setTarget(Side side, int count) {
        if (side == Side.ATTACKER) {
            targetAttacker = Math.max(0, count);
        } else {
            targetDefender = Math.max(0, count);
        }
    }

    public int alive() {
        return troopers.size();
    }

    public String info() {
        return String.format("假玩家小队：攻 %d / 守 %d 目标，当前存活 %d",
                targetAttacker, targetDefender, troopers.size());
    }

    public void clearAll(MinecraftServer server) {
        for (Trooper t : new ArrayList<>(troopers.values())) {
            remove(server, t);
        }
        troopers.clear();
        targetAttacker = 0;
        targetDefender = 0;
    }

    /** 按目标人数补员（不改动已有单位）。 */
    public void ensure(ServerMatch match, MinecraftServer server) {
        int att = countAlive(Side.ATTACKER);
        int def = countAlive(Side.DEFENDER);
        for (int i = 0; i < Math.max(0, targetAttacker - att); i++) {
            spawn(match, server, Side.ATTACKER);
        }
        for (int i = 0; i < Math.max(0, targetDefender - def); i++) {
            spawn(match, server, Side.DEFENDER);
        }
    }""",
    """    // ---------- 对外接口 ----------

    /** 设定某侧**目标总人数（含真人）**；0 = 不填充。 */
    public void setTarget(Side side, int count) {
        if (side == Side.ATTACKER) {
            targetAttacker = Math.max(0, count);
        } else {
            targetDefender = Math.max(0, count);
        }
    }

    public int targetOf(Side side) {
        return side == Side.ATTACKER ? targetAttacker : targetDefender;
    }

    public void setLobbyPresence(boolean on) {
        this.lobbyPresence = on;
    }

    public int alive() {
        return troopers.size();
    }

    /** 累计被真人顶替的次数（管理台/诊断）。 */
    public int humansEngaged() {
        return takeoverCount;
    }

    public String info() {
        return String.format("假玩家小队：攻 %d / 守 %d 目标（含真人），当前 BOT %d，已热顶替 %d 次%s",
                targetAttacker, targetDefender, troopers.size(), takeoverCount,
                lastTakeover.isEmpty() ? "" : "；最近：" + lastTakeover);
    }

    public void clearAll(MinecraftServer server) {
        for (Trooper t : new ArrayList<>(troopers.values())) {
            remove(server, t);
        }
        troopers.clear();
        targetAttacker = 0;
        targetDefender = 0;
        lastTakeover = "";
    }

    /**
     * <b>编制对账</b>——整套「非战斗 BOT + 真人热顶替」的核心。
     *
     * <p>不变量：**某侧（真人 + BOT）总数 == 该侧目标人数**。据此：
     * <ul>
     *   <li>真人进服并被分到某侧 → 该侧超编 → <b>立刻移除一个同侧 BOT</b>（热顶替），
     *       真人直接占住那个名额与位置，无需改目标数、无需重开回合；</li>
     *   <li>真人退服 → 该侧欠编 → 下一轮对账补回一个 BOT；</li>
     *   <li>大厅阶段同样执行（{@link #lobbyPresence}）→ 空服也始终是成建制双方。</li>
     * </ul>
     * 顶替优先裁掉**最后补进来**的那个（LinkedHashMap 尾部），让先来的单位位置稳定。
     */
    public void reconcile(ServerMatch match, MinecraftServer server) {
        // 1) 清扫阵亡单位：玩家壳死亡后仍会留在玩家列表等待重生，必须显式摘除，
        //    否则会累积「幽灵在线」。摘除后按**同名**重建，保持身份稳定
        //    （避免玩家列表不停 join/leave、playerdata 无限增长）。
        for (Trooper t : new ArrayList<>(troopers.values())) {
            ServerPlayerEntity bot = entity(server, t);
            if (bot == null) {
                if (tickCounter - t.spawnTick > 40) {
                    troopers.remove(t.id);          // 实体确实没了（如被 /kill 且未入列）
                }
                continue;
            }
            if (bot.isAlive()) {
                continue;
            }
            BotPlayerFactory.remove(server, bot);
            respawn(match, server, t);
        }
        if (!lobbyPresence && match.game().phase() != MatchPhase.BATTLE) {
            return;                                 // 仅战斗中填充
        }
        // 2) 逐侧对账：真人多了裁 BOT（热顶替），BOT 少了补 BOT
        for (Side side : new Side[]{Side.ATTACKER, Side.DEFENDER}) {
            int target = targetOf(side);
            int humans = countHumans(match, server, side);
            int wantBots = Math.max(0, target - humans);
            List<Trooper> mine = sideTroopers(side);
            if (mine.size() > wantBots) {
                for (int i = 0, drop = mine.size() - wantBots; i < drop; i++) {
                    Trooper t = mine.get(mine.size() - 1 - i);   // 后进先出
                    remove(server, t);
                    takeoverCount++;
                    lastTakeover = side.labelCn + " " + t.name + " → 真人接管（真人 " + humans
                            + " / 目标 " + target + "）";
                    BreakfrontServer.LOGGER.info(
                            "[BF-Bot] 真人热顶替：移除 {} 侧 BOT {}（真人 {}，目标 {}）",
                            side.labelCn, t.name, humans, target);
                }
            } else if (mine.size() < wantBots) {
                for (int i = 0, n = wantBots - mine.size(); i < n; i++) {
                    spawn(match, server, side);
                }
            }
        }
    }

    /** 兼容旧调用点：按当前目标补齐编制（现在等价于一次对账）。 */
    public void ensure(ServerMatch match, MinecraftServer server) {
        reconcile(match, server);
    }""",
)

E(
    BS,
    "bs-tick",
    """    public void tick(ServerMatch match, MinecraftServer server) {
        if (troopers.isEmpty()) {
            return;
        }
        tickCounter++;
        boolean upkeep = tickCounter % MAINTAIN_EVERY_TICKS == 0;
        // 每 20 tick：清扫阵亡并补员（真人死亡也是这个节奏被系统感知）
        if (tickCounter % 20 == 0) {
            sweepAndReinforce(match, server);
        }
        if (match.game().phase() != MatchPhase.BATTLE) {""",
    """    public void tick(ServerMatch match, MinecraftServer server) {
        tickCounter++;
        boolean upkeep = tickCounter % MAINTAIN_EVERY_TICKS == 0;
        // 每 1s 编制对账（战斗/大厅一致）：
        //   大厅 = 维持「非战斗 BOT」编制 + 真人进服即时热顶替；
        //   战斗 = 阵亡同名补员 + 真人顶替。
        if (tickCounter % 20 == 0) {
            reconcile(match, server);
        }
        if (troopers.isEmpty()) {
            return;
        }
        if (match.game().phase() != MatchPhase.BATTLE) {""",
)

E(
    BS,
    "bs-sweep",
    """    /** 清扫阵亡 bot 并**同名重生**（编制固定，不新增玩家身份）。 */
    private void sweepAndReinforce(ServerMatch match, MinecraftServer server) {
        for (Trooper t : new ArrayList<>(troopers.values())) {
            ServerPlayerEntity bot = entity(server, t);
            if (bot != null && bot.isAlive()) {
                continue;
            }
            if (bot != null) {
                // 玩家实体死亡后仍留在玩家列表（等待重生），必须显式摘除，否则会"幽灵在线"
                BotPlayerFactory.remove(server, bot);
            }
            respawn(match, server, t);
        }
    }""",
    """    /** 该侧**真人**数量（按 TeamManager 归属；AI 假玩家不计入——它们的名额就是用来被顶替的）。 */
    private int countHumans(ServerMatch match, MinecraftServer server, Side side) {
        int n = 0;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (BotPlayerFactory.isBot(p)) {
                continue;
            }
            if (match.teams().sideOf(p.getUuid()) == side) {
                n++;
            }
        }
        return n;
    }

    /** 该侧当前 BOT 名册（LinkedHashMap 保序：尾部 = 最后补进来的）。 */
    private List<Trooper> sideTroopers(Side side) {
        List<Trooper> out = new ArrayList<>();
        for (Trooper t : troopers.values()) {
            if (t.side == side) {
                out.add(t);
            }
        }
        return out;
    }""",
)

# ==========================================================================
# D. BreakfrontCommands —— /bf npc 指向假玩家小队
# ==========================================================================
E(
    BC,
    "bc-npcnode",
    """    private static LiteralArgumentBuilder<ServerCommandSource> npcNode() {
        return literal("npc").requires(s -> s.hasPermissionLevel(2))
                .then(literal("status").executes(ctx -> {
                    var match = BreakfrontServer.match();
                    if (match == null) {
                        return 0;
                    }
                    send(ctx.getSource(), match.npc().info());
                    return 1;
                }))
                .then(literal("clear").executes(ctx -> {
                    var match = BreakfrontServer.match();
                    if (match != null) {
                        match.npc().clearAll(BreakfrontServer.server());
                    }
                    send(ctx.getSource(), "已清除全部 NPC（目标数归零）");
                    return 1;
                }))
                .then(npcSetNode("attacker", Side.ATTACKER))
                .then(npcSetNode("defender", Side.DEFENDER));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> npcSetNode(String label, Side side) {
        return literal("add").then(literal(label)
                .then(CommandManager.argument("n", IntegerArgumentType.integer(0, 64))
                        .executes(ctx -> {
                            var match = BreakfrontServer.match();
                            if (match == null) {
                                return 0;
                            }
                            int n = IntegerArgumentType.getInteger(ctx, "n");
                            match.npc().setTarget(side, n);
                            match.npc().topUp(match, BreakfrontServer.server());
                            send(ctx.getSource(), side.labelCn + " NPC 目标人数=" + n);
                            return 1;
                        })));
    }""",
    """    /**
     * {@code /bf npc …} —— AI BOT 编制运维（历史命令名保留，实现已统一到假玩家小队）。
     *
     * <p>「僵尸壳 NpcSquad」已整体移除，故此命令现在等价于 {@code /bf bot} 的编制控制。
     */
    private static LiteralArgumentBuilder<ServerCommandSource> npcNode() {
        return literal("npc").requires(s -> s.hasPermissionLevel(2))
                .then(literal("status").executes(ctx -> {
                    var match = BreakfrontServer.match();
                    if (match == null) {
                        return 0;
                    }
                    send(ctx.getSource(), match.bots().info());
                    return 1;
                }))
                .then(literal("clear").executes(ctx -> {
                    var match = BreakfrontServer.match();
                    if (match != null) {
                        match.bots().clearAll(BreakfrontServer.server());
                    }
                    send(ctx.getSource(), "已清除全部 AI BOT（目标数归零）");
                    return 1;
                }))
                .then(npcSetNode("attacker", Side.ATTACKER))
                .then(npcSetNode("defender", Side.DEFENDER));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> npcSetNode(String label, Side side) {
        return literal("add").then(literal(label)
                .then(CommandManager.argument("n", IntegerArgumentType.integer(0, 64))
                        .executes(ctx -> {
                            var match = BreakfrontServer.match();
                            if (match == null) {
                                return 0;
                            }
                            int n = IntegerArgumentType.getInteger(ctx, "n");
                            match.bots().setTarget(side, n);
                            match.bots().reconcile(match, BreakfrontServer.server());
                            send(ctx.getSource(), side.labelCn + " AI 编制目标（含真人）=" + n);
                            return 1;
                        })));
    }""",
)

E(
    BC,
    "bc-botnode-doc",
    """     * {@code /bf bot …} —— 假玩家小队（AI BOT 重构的玩家壳实现）运维入口。
     *
     * <p>与 {@code /bf npc}（僵尸壳）互斥：启用假玩家前会清空旧壳 NPC，
     * 避免两套 AI 同时在场互相叠加。试用建议先在 BATTLE 阶段（{@code /bf start}），
     * 非战斗阶段 bot 会站在原地不动。""",
    """     * {@code /bf bot …} —— AI 假玩家小队运维入口。
     *
     * <p>常态编制由大厅赛程自动维持（{@code /bf fill on}），真人进服会自动热顶替一个 BOT；
     * 这里的 {@code trial} / {@code clear} 是**手动接管**编制用的调试开关。""",
)

E(
    BC,
    "bc-bottrial",
    """                                    int n = IntegerArgumentType.getInteger(ctx, "n");
                                    match.npc().clearAll(server);      // 互斥：清空旧壳
                                    match.bots().setTarget(Side.ATTACKER, n);
                                    match.bots().setTarget(Side.DEFENDER, n);
                                    match.bots().ensure(match, server);
                                    send(ctx.getSource(), "已生成 " + n + "v" + n
                                            + " 假玩家小队（旧壳 NPC 已清空）；"
                                            + "清除用 /bf bot clear，查看用 /bf bot status");
                                    return 1;""",
    """                                    int n = IntegerArgumentType.getInteger(ctx, "n");
                                    match.bots().setTarget(Side.ATTACKER, n);
                                    match.bots().setTarget(Side.DEFENDER, n);
                                    match.bots().reconcile(match, server);
                                    send(ctx.getSource(), "已生成 " + n + "v" + n
                                            + " 假玩家小队；清除用 /bf bot clear，查看用 /bf bot status");
                                    return 1;""",
)

# ==========================================================================
# E. WebAdminConsole —— squaremap 瓦片代理 + AI 状态改读假玩家小队
# ==========================================================================
E(
    WA,
    "wa-route",
    """            } else if (path.equals("/bfadmin/api/worldinfo") && m.equalsIgnoreCase("GET")) {
                worldInfo(ex);""",
    """            } else if (path.equals("/bfadmin/api/worldinfo") && m.equalsIgnoreCase("GET")) {
                worldInfo(ex);
            } else if (path.startsWith("/bfadmin/api/sqm/") && m.equalsIgnoreCase("GET")) {
                sqmProxy(ex, path.substring("/bfadmin/api/sqm/".length()));""",
)

E(
    WA,
    "wa-sqmproxy",
    """    private static String randomToken() {
        byte[] b = new byte[18];""",
    """    // ================= squaremap 真实俯瞰图代理 =================

    /** squaremap 内置 Web 服务的本机地址（默认 8080；可用 -Dbreakfront.squaremap 覆盖）。 */
    private static final String SQM_BASE =
            System.getProperty("breakfront.squaremap", "http://127.0.0.1:8080");
    /** 同一进程复用连接池；瓦片很小，4s 连接超时足够。 */
    private static final java.net.http.HttpClient SQM_HTTP = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(4))
            .build();

    /**
     * 把 {@code /bfadmin/api/sqm/<sub>} 透明代理到 squaremap 的内置 Web 服务。
     *
     * <p>这样管理台只需暴露 25610 一个入口：浏览器拿 squaremap 渲染的**真实俯视瓦片**
     * （{@code tiles/<world>/<z>/<x>_<y>.png}）与 {@code tiles/settings.json}（世界/缩放元数据），
     * 无需把 squaremap 的 8080 端口暴露到公网。
     */
    private static void sqmProxy(HttpExchange ex, String sub) throws IOException {
        if (!auth(ex)) {
            json(ex, 401, "{\\"ok\\":false,\\"msg\\":\\"未授权或已过期\\"}");
            return;
        }
        if (sub.isEmpty() || sub.contains("..") || !sub.matches("[A-Za-z0-9_./-]+")) {
            respond(ex, 400, "bad path".getBytes(StandardCharsets.UTF_8), "text/plain");
            return;
        }
        try {
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(SQM_BASE + "/" + sub))
                    .timeout(java.time.Duration.ofSeconds(10)).GET().build();
            var resp = SQM_HTTP.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            int code = resp.statusCode();
            if (code != 200) {
                respond(ex, code == 404 ? 404 : 502, new byte[0], "text/plain");
                return;
            }
            String ct = resp.headers().firstValue("content-type").orElse(sqmContentType(sub));
            ex.getResponseHeaders().set("Content-Type", ct);
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=20");
            ex.sendResponseHeaders(200, resp.body().length);
            ex.getResponseBody().write(resp.body());
            ex.close();
        } catch (Exception e) {
            json(ex, 502, "{\\"ok\\":false,\\"msg\\":\\"squaremap 不可达（未安装/未启动/未渲染）："
                    + esc(e.getClass().getSimpleName()) + "\\"}");
        }
    }

    private static String sqmContentType(String sub) {
        if (sub.endsWith(".png")) {
            return "image/png";
        }
        if (sub.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (sub.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    private static String randomToken() {
        byte[] b = new byte[18];""",
)

E(
    WA,
    "wa-status-ai",
    """        // AI
        NpcSquad npc = match.npc();
        sb.append("\\"aiAlive\\":").append(npc == null ? 0 : npc.alive()).append(',');
        sb.append("\\"aiInfo\\":\\"").append(esc(npc == null ? "-" : npc.info())).append("\\",");""",
    """        // AI：假玩家小队（含大厅「非战斗 BOT」与真人热顶替计数）
        BotSquad bots = match.bots();
        sb.append("\\"aiAlive\\":").append(bots == null ? 0 : bots.alive()).append(',');
        sb.append("\\"aiTakeover\\":").append(bots == null ? 0 : bots.humansEngaged()).append(',');
        sb.append("\\"aiInfo\\":\\"").append(esc(bots == null ? "-" : bots.info())).append("\\",");""",
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
    print(f"已重放 {len(EDITS) - bad}/{len(EDITS)} 处 core 改动")
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
