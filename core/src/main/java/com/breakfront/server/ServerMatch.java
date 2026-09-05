package com.breakfront.server;

import com.breakfront.game.BreakthroughGame;
import com.breakfront.game.MatchPhase;
import com.breakfront.game.MatchResult;
import com.breakfront.game.Sector;
import com.breakfront.game.Side;
import com.breakfront.game.ZoneState;
import com.breakfront.map.SectorLayout;
import com.breakfront.net.MatchStatePayload;
import com.breakfront.net.ScoreboardPayload;
import com.breakfront.net.SectorEditPayload;
import com.breakfront.server.arena.ArenaViaduct;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 服务端对局运行时：把纯逻辑 BreakthroughGame 与真实玩家/世界接起来。
 *
 * - 每 tick 调用 game.tick(0.05)
 * - 按 ZoneAnchor 统计圈内双方人数并喂给占点逻辑
 * - 扇区配置（含坐标）来自 SectorLayout：启动装载 runDir/breakfront/sectors.json，
 *   缺失时回退内置 viaduct 布局；/bfs apply 可将编辑成果热应用到对局
 * - AI 自动填充（autoFill）：大厅有真人时按 16v16 补 bot 并在 5s 后自动开局，
 *   实现「单机/人少 = 1 个真人 + 其余 AI」；/bf fill off 关闭后回到双真人就绪规则
 */
public final class ServerMatch {

    /** 自动填充时每边目标总人数（真人 + AI）。 */
    private static final int FILL_TARGET = 16;

    private final TeamManager teams = new TeamManager();
    private final ScoreKeeper score = new ScoreKeeper();
    private final NpcSquad npc = new NpcSquad();
    private final Map<String, ZoneAnchor> anchors = new LinkedHashMap<>();
    private final List<String> zoneOrder = new ArrayList<>();
    private final Path runDir;

    private SectorLayout layout;           // 扇区配置（编辑器草稿即此物，坐标唯一来源）
    private BreakthroughGame game;         // 由 layout 重建（/bfs apply 时替换）
    private int syncCounter = 0;           // 状态广播节流：每 10 tick 一次
    private boolean visualsPlaced = false; // 据点空间标识（信标）只放一次
    private boolean arenaBuilt = false;    // 竞技场城市只建一次
    private boolean arenaSkipped = false;  // 使用外部世界时跳过自建城市
    private boolean envFixed = false;      // 环境锁定只做一次（白昼/禁刷怪/禁天气）

    /** 回合自动循环：结算展示 8 秒后自动重开下一局。 */
    private static final double ROUND_END_PAUSE = 8.0;
    private double endPause = -1;

    // ---- 出生/赛程（S1）----
    private boolean autostart;          // 关闭 AI 填充时：大厅双真实阵营人数达标自动开局
    private double lobbyTimer = -1;
    private MatchPhase lastPhase = MatchPhase.LOBBY;
    private final double[] attackerSpawn = {Double.NaN, Double.NaN}; // override {x,z}
    private final double[] defenderSpawn = {Double.NaN, Double.NaN};

    // ---- AI 自动填充（人机对战 / 单机=一真人其余AI）----
    private boolean autoFill = true;    // 默认开：有真人即 16v16 填充并自动开局
    private boolean autoArmed;          // bot 已补齐、等待开局
    private double autoTimer = -1;

    // ---- 扇区编辑器（/bfs）----
    private int editorSectorIdx;        // 当前编辑扇区指针
    private final Set<UUID> editorViewers = new HashSet<>();

    public ServerMatch(MinecraftServer server) {
        this.runDir = server.getRunDirectory();
        loadServerProps();
        this.layout = loadLayoutOrFallback();
        rebuildFromLayout();
    }

    // ================= 装载与重建 =================

    /** 启动装载：外部扇区配置优先，缺失/损坏回退内置 viaduct 布局。 */
    private SectorLayout loadLayoutOrFallback() {
        Path file = runDir.resolve("breakfront/sectors.json");
        if (Files.isRegularFile(file)) {
            try {
                SectorLayout loaded = SectorLayout.parse(
                        Files.readString(file, StandardCharsets.UTF_8));
                if (loaded.zoneCount() > 0) {
                    BreakfrontServer.LOGGER.info("[Breakfront] sectors loaded from {} ({} zones)",
                            file.getFileName(), loaded.zoneCount());
                    return loaded;
                }
            } catch (Exception e) {
                BreakfrontServer.LOGGER.warn("[Breakfront] sectors.json invalid ({}), fallback default",
                        e.toString());
            }
        }
        return SectorLayout.defaultViaduct();
    }

    /** 以当前 layout 重建纯逻辑对局 + 锚点索引（启动与 /bfs apply 共用）。 */
    private void rebuildFromLayout() {
        this.game = new BreakthroughGame(layout.toGameSectors());
        anchors.clear();
        zoneOrder.clear();
        for (SectorLayout.SectorDef def : layout.sectors()) {
            for (SectorLayout.Zone z : def.zones()) {
                anchors.put(z.id(), new ZoneAnchor(z.id(), z.x(), z.z(), z.radius()));
                zoneOrder.add(z.id());
            }
        }
        visualsPlaced = false;
        score.reset();
        BreakfrontServer.LOGGER.info("[Breakfront] layout applied ({} sectors, {} zones)",
                layout.sectorCount(), layout.zoneCount());
    }

    /** 应用扇区配置：先落盘，再重建对局并回到大厅（/bfs apply）。 */
    public String applyLayout(MinecraftServer server) {
        if (layout.zoneCount() == 0) {
            return "当前布局没有任何据点，拒绝应用（请先 /bfs here 添加）";
        }
        String save = saveLayout();
        rebuildFromLayout();
        game.returnToLobby();
        autoArmed = false;
        autoTimer = -1;
        npc.clearAll(server);
        BreakfrontServer.LOGGER.info("[Breakfront] layout applied to live match (lobby)");
        pushEditorPreview(server);
        return "已应用并保存扇区布局，对局回到大厅（自动填充将在有真人后重开）：\n" + layout.toText();
    }

    // ================= 服务端主循环 =================

    public void tick(MinecraftServer server) {
        // 单机（单人世界）与外部地图标记都跳过自建城
        if (server.isSingleplayer() && !arenaSkipped) {
            arenaSkipped = true;
            arenaBuilt = true;
        }
        if (!arenaBuilt && !arenaSkipped) {
            try {
                Path flag = runDir.resolve("breakfront.map.external");
                if (Files.exists(flag)) {
                    arenaSkipped = true;
                    arenaBuilt = true;
                }
            } catch (Exception ignored) {
                // 目录不可读则忽略，走默认
            }
        }
        if (!arenaBuilt && !arenaSkipped) {
            arenaBuilt = ArenaViaduct.tryBuild(server.getOverworld());
            if (arenaBuilt) {
                BreakfrontServer.LOGGER.info("[Breakfront] viaduct arena built ({} zones)", anchors.size());
            }
        }
        if (!envFixed) {
            envFixed = fixArenaEnvironment(server);
        }
        game.tick(0.05);
        syncCounter++;
        boolean syncTick = syncCounter % 10 == 0; // 每 0.5s 广播一次状态
        if (syncTick && !server.getPlayerManager().getPlayerList().isEmpty()) {
            broadcastState(server);
            if (syncCounter % 20 == 0) { // 每 1s 广播比分/击杀榜
                broadcastScore(server);
            }
        }
        // 回合自动循环：结算展示 8 秒后自动重开下一局（队伍/锚点不变）
        if (game.phase() == MatchPhase.ROUND_END) {
            if (endPause < 0) {
                endPause = ROUND_END_PAUSE;
                BreakfrontServer.LOGGER.info("[Breakfront] round over ({}), next round in {}s",
                        game.result(), String.format("%.0f", ROUND_END_PAUSE));
            }
            endPause -= 0.05;
            if (endPause <= 0) {
                endPause = -1;
                beginRound();
                visualsPlaced = false;
                BreakfrontServer.LOGGER.info("[Breakfront] auto started next round");
            }
        } else {
            endPause = -1;
        }
        // S1 赛程：AI 填充/自动开局 + 进入 BATTLE 的上升沿做全员部署传送
        tickAutoPlay(server);
        if (game.phase() == MatchPhase.BATTLE && lastPhase != MatchPhase.BATTLE) {
            teleportAllToSpawns(server);
        }
        lastPhase = game.phase();
        if (game.phase() != MatchPhase.BATTLE) {
            return;
        }
        if (!visualsPlaced) {
            placeZoneVisuals(server.getOverworld());
            visualsPlaced = true;
        }
        npc.tick(this, server); // NPC 增援向目标点推进
        var overworld = server.getOverworld();
        for (int idx = 0; idx < zoneOrder.size(); idx++) {
            ZoneAnchor anchor = anchors.get(zoneOrder.get(idx));
            int attackers = 0;
            int defenders = 0;
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (player.getWorld() != overworld || player.isSpectator()) {
                    continue;
                }
                Side side = teams.sideOf(player.getUuid());
                if (side == null) {
                    continue;
                }
                if (anchor.contains(player.getX(), player.getZ())) {
                    if (side == Side.ATTACKER) {
                        attackers++;
                    } else {
                        defenders++;
                    }
                }
            }
            // NPC 增援计入圈内人数（zombie + 阵营 tag）
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
            game.applyZonePresence(idx, attackers, defenders, 0.05);
        }
    }

    /**
     * 开局赛程：
     * - autoFill 开 & 大厅有真人 → 双阵营补齐至 16 并 5 秒后自动开局（单人=1 真人其余 AI）
     * - autoFill 关 → 沿用旧规则：双真实阵营就绪自动开（/bf autostart on）或 /bf start 手动开
     */
    private void tickAutoPlay(MinecraftServer server) {
        if (game.phase() != MatchPhase.LOBBY) {
            return; // 局中/倒计时/结算均不干预
        }
        int humans = server.getPlayerManager().getPlayerList().size();
        if (autoFill && humans > 0) {
            if (!autoArmed) {
                npc.setTarget(Side.ATTACKER, FILL_TARGET);
                npc.setTarget(Side.DEFENDER, FILL_TARGET);
                npc.topUp(this, server);
                autoArmed = true;
                autoTimer = 5.0;
                BreakfrontServer.LOGGER.info(
                        "[Breakfront] AI fill {}v{} ({} human), round in 5s", FILL_TARGET, FILL_TARGET, humans);
            } else {
                autoTimer -= 0.05;
                if (autoTimer <= 0) {
                    autoTimer = -1;
                    beginRound();
                    visualsPlaced = false;
                    BreakfrontServer.LOGGER.info("[Breakfront] auto round begun (fill on)");
                }
            }
            return;
        }
        if (autoArmed) {
            autoArmed = false;
            autoTimer = -1;
        }
        if (!autoFill) {
            tickAutoStartLegacy(server);
        }
    }

    /** 旧版自动开局（AI 填充关闭时）：双阵营真实玩家 ≥1 且总数 ≥2 → 5 秒开局。 */
    private void tickAutoStartLegacy(MinecraftServer server) {
        if (game.phase() != MatchPhase.LOBBY || !autostart) {
            lobbyTimer = -1;
            return;
        }
        int att = onlineCount(Side.ATTACKER, server);
        int def = onlineCount(Side.DEFENDER, server);
        if (att >= 1 && def >= 1 && att + def >= 2) {
            if (lobbyTimer < 0) {
                lobbyTimer = 5;
                BreakfrontServer.LOGGER.info("[Breakfront] autostart: {}v{} online, round in 5s", att, def);
            }
            lobbyTimer -= 0.05;
            if (lobbyTimer <= 0) {
                lobbyTimer = -1;
                beginRound();
                visualsPlaced = false;
                teleportAllToSpawns(server);
                BreakfrontServer.LOGGER.info("[Breakfront] autostart round begun");
            }
        } else {
            lobbyTimer = -1;
        }
    }

    private int onlineCount(Side side, MinecraftServer server) {
        int n = 0;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (teams.sideOf(p.getUuid()) == side) {
                n++;
            }
        }
        return n;
    }

    /**
     * 首次进入战斗时放置据点空间标识：中心信标光柱。
     * 地面区域不铺实心盘 —— 「描边高亮」由客户端世界渲染负责（见 client WorldZoneRings），
     * 信标光柱保留作为非 breakfront-client 玩家的兜底提示。
     */
    private void placeZoneVisuals(ServerWorld world) {
        for (ZoneAnchor anchor : anchors.values()) {
            int cx = (int) anchor.x();
            int cz = (int) anchor.z();
            int topY = world.getTopY(Heightmap.Type.WORLD_SURFACE, cx, cz);
            if (topY <= world.getBottomY()) {
                continue;
            }
            world.setBlockState(new BlockPos(cx, topY + 1, cz), Blocks.BEACON.getDefaultState(), 3);
        }
    }

    /** 据点地表高度（信标底座所在 Y = 最高固体上方一格）。 */
    private double anchorGroundY(ServerWorld world, ZoneAnchor anchor) {
        int cx = (int) anchor.x();
        int cz = (int) anchor.z();
        int topY = world.getTopY(Heightmap.Type.WORLD_SURFACE, cx, cz);
        return topY <= world.getBottomY() ? 64.0 : topY + 1.0;
    }

    /** 据点锚点坐标摘要（供 /bf status 展示，方便传送验证）。 */
    public String zoneAnchorsText() {
        StringBuilder sb = new StringBuilder();
        int gi = 0;
        for (String id : zoneOrder) {
            ZoneAnchor a = anchors.get(id);
            char letter = (gi >= 0 && gi < 26) ? (char) ('A' + gi) : '?';
            sb.append('\n').append(letter).append(" (").append(a.zoneId()).append(") @ (x=")
                    .append(String.format("%.1f", a.x())).append(", z=")
                    .append(String.format("%.1f", a.z())).append(", r=")
                    .append(String.format("%.0f", a.radius())).append(')');
            gi++;
        }
        return sb.toString();
    }

    /** 竞技场环境锁定：恒为白天、禁止刷怪/天气/生物破坏，并清空既有生物。 */
    private boolean fixArenaEnvironment(MinecraftServer server) {
        String[] cmds = {
                "gamerule doDaylightCycle false",
                "gamerule doWeatherCycle false",
                "gamerule doMobSpawning false",
                "gamerule mobGriefing false",
                "time set 6000",
                "weather clear",
                "kill @e[type=!minecraft:player,distance=..200]"
        };
        var source = server.getCommandSource();
        for (String cmd : cmds) {
            server.getCommandManager().executeWithPrefix(source, cmd);
        }
        return true;
    }

    /** 跳过自建城市（配合外部世界导入的地图）。 */
    public void setArenaSkipped(boolean skipped) {
        this.arenaSkipped = skipped;
        if (skipped) {
            this.arenaBuilt = true;
        }
        this.visualsPlaced = false;
    }

    /** 把某个据点锚点移动到指定坐标（就地重新铺标识）。 */
    public boolean moveAnchor(int zoneIndex, double x, double z) {
        if (zoneIndex < 0 || zoneIndex >= zoneOrder.size()) {
            return false;
        }
        String id = zoneOrder.get(zoneIndex);
        ZoneAnchor old = anchors.get(id);
        anchors.put(id, new ZoneAnchor(id, x, z, old.radius()));
        visualsPlaced = false;
        return true;
    }

    /** 打包并广播对局状态给所有在线玩家。 */
    private void broadcastState(MinecraftServer server) {
        ServerWorld world = server.getOverworld();
        var zones = new ArrayList<MatchStatePayload.ZoneStateView>();
        for (ZoneState zone : game.currentSector().zones()) {
            ZoneAnchor anchor = anchors.get(zone.id());
            if (anchor == null) {
                continue;
            }
            int gi = zoneOrder.indexOf(zone.id());
            char letter = (gi >= 0 && gi < 26) ? (char) ('A' + gi) : '?';
            zones.add(new MatchStatePayload.ZoneStateView(
                    zone.id(),
                    String.valueOf(letter),
                    zone.owner().ordinal(),
                    (float) zone.meter(),
                    anchor.x(),
                    anchor.z(),
                    anchorGroundY(world, anchor),
                    (float) anchor.radius()));
        }
        var payload = new MatchStatePayload(
                game.phase().ordinal(),
                game.attackerTickets(),
                (float) game.matchRemaining(),
                (float) game.countdownRemaining(),
                game.sectorIndex(),
                game.sectors().size(),
                zones,
                (int) teams.count(Side.ATTACKER),
                (int) teams.count(Side.DEFENDER),
                game.result().ordinal());
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    /** 广播比分/击杀榜（每 1s）。 */
    private void broadcastScore(MinecraftServer server) {
        var rows = new ArrayList<ScoreboardPayload.Row>();
        for (ScoreKeeper.Entry e : score.top(12)) {
            rows.add(new ScoreboardPayload.Row(e.name, e.sideOrdinal, e.kills, e.deaths, e.headshots));
        }
        var payload = new ScoreboardPayload(score.attackerKills(), score.defenderKills(), rows);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    public String scoreText() {
        StringBuilder sb = new StringBuilder();
        sb.append("攻方击杀 ").append(score.attackerKills())
                .append("  vs  守方击杀 ").append(score.defenderKills());
        if (score.isEmpty()) {
            return sb.toString() + "\n暂无战绩";
        }
        for (ScoreKeeper.Entry e : score.top(10)) {
            sb.append('\n').append(e.name).append(" [")
                    .append(e.sideOrdinal == 0 ? "攻" : "守").append("] ")
                    .append(e.kills).append("杀 ").append(e.deaths).append("死");
            if (e.headshots > 0) {
                sb.append(" (").append(e.headshots).append("爆头)");
            }
        }
        return sb.toString();
    }

    public BreakthroughGame game() {
        return game;
    }

    public TeamManager teams() {
        return teams;
    }

    public SectorLayout layout() {
        return layout;
    }

    /** 开局统一入口：重开状态机、清战绩、补 NPC、复位据点标识。 */
    public void beginRound() {
        game.startRound();
        score.reset();
        visualsPlaced = false;
        npc.beginRound(this, BreakfrontServer.server());
    }

    public ScoreKeeper score() {
        return score;
    }

    public NpcSquad npc() {
        return npc;
    }

    /** 供 NpcSquad 使用的公开坐标（出生 y 含地面）。 */
    public double[] spawnsFor(Side side, ServerWorld world) {
        return spawnFor(side, world);
    }

    public int zoneIndex(String zoneId) {
        return zoneOrder.indexOf(zoneId);
    }

    /** 据点中心 {x, z, y}（y=地表+1），序号越界返回 null。 */
    public double[] zoneCenter(int globalIndex) {
        if (globalIndex < 0 || globalIndex >= zoneOrder.size()) {
            return null;
        }
        ZoneAnchor a = anchors.get(zoneOrder.get(globalIndex));
        if (a == null) {
            return null;
        }
        return new double[]{a.x(), a.z(), 0};
    }

    /** NPC 被玩家击杀：只给击杀者记分，不建 NPC 条目。 */
    public void recordBotKill(ServerPlayerEntity killer, int botSideOrd) {
        Side ks = teams.sideOf(killer.getUuid());
        score.creditKill(killer.getUuid(), killer.getGameProfile().getName(),
                ks == null ? -1 : ks.ordinal());
    }

    /** 记录一笔击杀（第 1 层 vanilla 事件调用；爆头标记由第 2 层富化后补录）。 */
    public void recordKill(ServerPlayerEntity victim,
                           net.minecraft.entity.LivingEntity killer) {
        Side vs = teams.sideOf(victim.getUuid());
        int vSide = vs == null ? 1 : vs.ordinal();
        if (killer instanceof ServerPlayerEntity kp) {
            Side ks = teams.sideOf(kp.getUuid());
            int kSide = ks == null ? -1 : ks.ordinal();
            score.record(victim.getUuid(), victim.getGameProfile().getName(), vSide,
                    kp.getUuid(), kp.getGameProfile().getName(), kSide, false);
        } else {
            score.record(victim.getUuid(), victim.getGameProfile().getName(), vSide,
                    null, null, -1, false);
        }
    }

    // ================= 玩家进出 =================

    /** 玩家进服：自动补位；战局中直接部署到出生区并钉重生点。 */
    public void onPlayerJoin(MinecraftServer server, ServerPlayerEntity player) {
        if (teams.sideOf(player.getUuid()) == null) {
            teams.assignLeast(player.getUuid());
        }
        MatchPhase ph = game.phase();
        if (ph == MatchPhase.BATTLE || ph == MatchPhase.COUNTDOWN) {
            deployPlayer(server, player);
        }
    }

    public void onPlayerLeft(UUID playerId) {
        teams.leave(playerId);
        editorViewers.remove(playerId);
    }

    /** 战中玩家死亡：把重生点钉在己方部署区（vanilla 复活即回防线）。 */
    public void onPlayerDied(MinecraftServer server, ServerPlayerEntity player) {
        Side side = teams.sideOf(player.getUuid());
        if (side == null) {
            return;
        }
        MatchPhase ph = game.phase();
        if (ph != MatchPhase.BATTLE && ph != MatchPhase.COUNTDOWN) {
            return;
        }
        double[] sp = spawnFor(side, server.getOverworld());
        exec(server, String.format("spawnpoint %s %.1f %.1f %.1f",
                player.getGameProfile().getName(), sp[0], sp[1], sp[2]));
    }

    private void deployPlayer(MinecraftServer server, ServerPlayerEntity player) {
        Side side = teams.sideOf(player.getUuid());
        if (side == null) {
            return;
        }
        double[] sp = spawnFor(side, server.getOverworld());
        exec(server, String.format("tp %s %.1f %.1f %.1f",
                player.getGameProfile().getName(), sp[0], sp[1], sp[2]));
        exec(server, String.format("spawnpoint %s %.1f %.1f %.1f",
                player.getGameProfile().getName(), sp[0], sp[1], sp[2]));
    }

    private void teleportAllToSpawns(MinecraftServer server) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            Side side = teams.sideOf(p.getUuid());
            if (side != null) {
                deployPlayer(server, p);
            }
        }
    }

    /** 出生坐标（含 Y），攻/守默认锚定在首/末据点的阵营侧；可 /bf spawns set 覆盖。 */
    private double[] spawnFor(Side side, ServerWorld world) {
        double[] ov = side == Side.ATTACKER ? attackerSpawn : defenderSpawn;
        if (!Double.isNaN(ov[0])) {
            return new double[]{ov[0], groundY(world, ov[0], ov[1]) + 1, ov[1]};
        }
        int idx = side == Side.ATTACKER ? 0 : Math.max(0, zoneOrder.size() - 1);
        ZoneAnchor a = anchors.get(zoneOrder.get(idx));
        if (a == null) {
            return new double[]{8, 70, 8};
        }
        double dir = side == Side.ATTACKER ? -1 : 1;
        double sx = a.x() + dir * (a.radius() + 5);
        return new double[]{sx, anchorGroundY(world, a) + 1, a.z()};
    }

    private double groundY(ServerWorld world, double x, double z) {
        int topY = world.getTopY(Heightmap.Type.WORLD_SURFACE, (int) x, (int) z);
        return topY <= world.getBottomY() ? 64.0 : topY + 1.0;
    }

    private static void exec(MinecraftServer server, String cmd) {
        try {
            server.getCommandManager().executeWithPrefix(server.getCommandSource(), cmd);
        } catch (Exception e) {
            BreakfrontServer.LOGGER.warn("[Breakfront] cmd failed: {} ({})", cmd, e.toString());
        }
    }

    // ================= /bf autostart / fill =================

    public boolean autostartEnabled() {
        return autostart;
    }

    public void setAutostart(boolean on) {
        autostart = on;
        if (!on) {
            lobbyTimer = -1;
        }
        saveServerProps();
    }

    public boolean autoFillEnabled() {
        return autoFill;
    }

    /** 开/关 AI 自动填充 + 自动开局（持久化到 runDir/breakfront-server.properties）。 */
    public void setAutoFill(boolean on) {
        autoFill = on;
        autoArmed = false;
        autoTimer = -1;
        if (!on) {
            npc.clearAll(BreakfrontServer.server());
        }
        saveServerProps();
    }

    public boolean setSpawnOverride(Side side, double x, double z) {
        double[] t = side == Side.ATTACKER ? attackerSpawn : defenderSpawn;
        t[0] = x;
        t[1] = z;
        return true;
    }

    public String spawnsText(MinecraftServer server) {
        if (server == null) {
            return "\n（服务器未就绪）";
        }
        ServerWorld world = server.getOverworld();
        double[] a = spawnFor(Side.ATTACKER, world);
        double[] d = spawnFor(Side.DEFENDER, world);
        return String.format("\n攻方出生 (%.1f, %.1f, %.1f)\n守方出生 (%.1f, %.1f, %.1f)",
                a[0], a[1], a[2], d[0], d[1], d[2]);
    }

    private void loadServerProps() {
        try {
            Path file = runDir.resolve("breakfront-server.properties");
            if (Files.isRegularFile(file)) {
                for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int eq = line.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String k = line.substring(0, eq).trim().toLowerCase();
                    String v = line.substring(eq + 1).trim();
                    if (k.equals("fill")) {
                        autoFill = v.equalsIgnoreCase("on") || v.equals("1") || v.equals("true");
                    } else if (k.equals("autostart")) {
                        autostart = v.equalsIgnoreCase("on") || v.equals("1") || v.equals("true");
                    }
                }
            }
        } catch (IOException e) {
            // 保持默认
        }
    }

    private void saveServerProps() {
        try {
            Path file = runDir.resolve("breakfront-server.properties");
            Files.createDirectories(file.getParent());
            Files.writeString(file, String.join("\n",
                    "# BREAKFRONT 服务端运行开关",
                    "fill=" + (autoFill ? "on" : "off"),
                    "autostart=" + (autostart ? "on" : "off")) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            BreakfrontServer.LOGGER.warn("[Breakfront] cannot save server props: {}", e.toString());
        }
    }

    // ================= 扇区编辑器（/bfs 操作） =================

    public boolean editorActive(UUID playerId) {
        return editorViewers.contains(playerId);
    }

    public int editorViewersCount() {
        return editorViewers.size();
    }

    public String editorOn(MinecraftServer server, UUID playerId) {
        editorViewers.add(playerId);
        ensureEditorSector();
        pushEditorPreview(server);
        return "扇区编辑器已开启：准星对准方块用 /bfs here [半径] 添加据点；/bfs help 看全部命令。当前扇区: "
                + currentEditorSectorName();
    }

    public String editorOff(MinecraftServer server, UUID playerId) {
        editorViewers.remove(playerId);
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(playerId);
        if (p != null) {
            ServerPlayNetworking.send(p, new SectorEditPayload(false, 0, List.of()));
        }
        return "扇区编辑器已关闭（预览已清除）";
    }

    public int editorSectorIdx() {
        return editorSectorIdx;
    }

    public String currentEditorSectorName() {
        ensureEditorSector();
        return (editorSectorIdx + 1) + "/" + layout.sectorCount() + " 「"
                + layout.sectors().get(editorSectorIdx).name() + "」"
                + layout.sectors().get(editorSectorIdx).zones().size() + " 个据点";
    }

    /** 确保至少有一个扇区且指针有效（空布局 → 自动建「扇区一」）。 */
    private void ensureEditorSector() {
        if (layout.sectors().isEmpty()) {
            layout.sectors().add(new SectorLayout.SectorDef("扇区一"));
        }
        if (editorSectorIdx < 0 || editorSectorIdx >= layout.sectors().size()) {
            editorSectorIdx = 0;
        }
    }

    /** 在指定扇区计算唯一据点 id（如 A1/A2/B1），按「扇区字母 + 序号」。 */
    private String nextZoneId(SectorLayout.SectorDef def) {
        int si = layout.sectors().indexOf(def);
        if (si < 0) {
            si = editorSectorIdx;
        }
        char letter = si < 26 ? (char) ('A' + si) : 'Z';
        int n = def.zones().size() + 1;
        String id;
        do {
            id = "" + letter + n;
            n++;
        } while (findZoneIndex(id) >= 0);
        return id;
    }

    /** 全布局范围内查找据点，返回 {sectorIdx, zoneIdx}；找不到返回 -1。 */
    private int[] findZoneIndex(String id) {
        for (int si = 0; si < layout.sectors().size(); si++) {
            List<SectorLayout.Zone> zones = layout.sectors().get(si).zones();
            for (int zi = 0; zi < zones.size(); zi++) {
                if (zones.get(zi).id().equals(id)) {
                    return new int[]{si, zi};
                }
            }
        }
        return null;
    }

    /** /bfs here：在准星所指方块位置向当前扇区添加据点。 */
    public String editorAdd(MinecraftServer server, double x, double z, double radius) {
        ensureEditorSector();
        SectorLayout.SectorDef def = layout.sectors().get(editorSectorIdx);
        double r = Math.max(1.0, Math.min(64.0, radius));
        String id = nextZoneId(def);
        def.addZone(new SectorLayout.Zone(id, x, z, r));
        pushEditorPreview(server);
        return String.format("已添加据点 %s → 扇区 %d 「%s」 @ (%.1f, %.1f, r=%.0f)",
                id, editorSectorIdx + 1, def.name(), x, z, r);
    }

    /** /bfs move：移动已有据点圆心。 */
    public String editorMove(MinecraftServer server, String id, double x, double z) {
        int[] idx = findZoneIndex(id);
        if (idx == null) {
            return "找不到据点 " + id + "（/bfs list 查看）";
        }
        SectorLayout.Zone old = layout.sectors().get(idx[0]).zones().get(idx[1]);
        SectorLayout.Zone next = new SectorLayout.Zone(id, x, z, old.radius());
        layout.sectors().get(idx[0]).zones().set(idx[1], next);
        pushEditorPreview(server);
        return String.format("据点 %s 移至 (%.1f, %.1f)", id, x, z);
    }

    /** /bfs resize：改据点半径。 */
    public String editorResize(MinecraftServer server, String id, double radius) {
        int[] idx = findZoneIndex(id);
        if (idx == null) {
            return "找不到据点 " + id + "（/bfs list 查看）";
        }
        SectorLayout.Zone old = layout.sectors().get(idx[0]).zones().get(idx[1]);
        double r = Math.max(1.0, Math.min(64.0, radius));
        layout.sectors().get(idx[0]).zones().set(idx[1],
                new SectorLayout.Zone(id, old.x(), old.z(), r));
        pushEditorPreview(server);
        return String.format("据点 %s 半径改为 %.0f", id, r);
    }

    /** /bfs remove：删除指定据点。 */
    public String editorRemove(MinecraftServer server, String id) {
        for (SectorLayout.SectorDef def : layout.sectors()) {
            if (def.removeZone(id)) {
                pushEditorPreview(server);
                return "已删除据点 " + id;
            }
        }
        return "找不到据点 " + id + "（/bfs list 查看）";
    }

    /** /bfs undo：删除最近添加的一个据点（从最后一个扇区倒序找）。 */
    public String editorUndo(MinecraftServer server) {
        for (int si = layout.sectors().size() - 1; si >= 0; si--) {
            List<SectorLayout.Zone> zones = layout.sectors().get(si).zones();
            if (!zones.isEmpty()) {
                SectorLayout.Zone last = zones.remove(zones.size() - 1);
                if (editorSectorIdx >= layout.sectors().size()) {
                    editorSectorIdx = layout.sectors().size() - 1;
                }
                pushEditorPreview(server);
                return "已撤销：删除据点 " + last.id();
            }
        }
        return "没有可撤销的据点";
    }

    /** /bfs sector next：前进一个扇区（到底则新建）。 */
    public String editorSectorNext(MinecraftServer server) {
        ensureEditorSector();
        if (editorSectorIdx >= layout.sectors().size() - 1) {
            layout.sectors().add(new SectorLayout.SectorDef("扇区" + (layout.sectors().size() + 1)));
        }
        editorSectorIdx++;
        pushEditorPreview(server);
        return "编辑指针 → " + currentEditorSectorName();
    }

    /** /bfs sector prev：回退一个扇区（已在第一个则不动）。 */
    public String editorSectorPrev(MinecraftServer server) {
        ensureEditorSector();
        if (editorSectorIdx > 0) {
            editorSectorIdx--;
        }
        pushEditorPreview(server);
        return "编辑指针 → " + currentEditorSectorName();
    }

    /** /bfs sector name：重命名当前扇区。 */
    public String editorSectorRename(MinecraftServer server, String name) {
        ensureEditorSector();
        layout.sectors().get(editorSectorIdx).setName(name);
        pushEditorPreview(server);
        return "当前扇区已重命名：" + currentEditorSectorName();
    }

    /** /bfs clear：清空全部扇区（保留一个空扇区便于重新开始）。 */
    public String editorClear(MinecraftServer server) {
        layout.sectors().clear();
        layout.sectors().add(new SectorLayout.SectorDef("扇区一"));
        editorSectorIdx = 0;
        pushEditorPreview(server);
        return "布局已清空（保留空「扇区一」，用 /bfs here 开始划分）";
    }

    /** /bfs save：落盘到 runDir/breakfront/sectors.json。 */
    public String saveLayout() {
        if (layout.zoneCount() == 0) {
            return "布局为空，不保存";
        }
        try {
            Path file = runDir.resolve("breakfront/sectors.json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, layout.toJson(), StandardCharsets.UTF_8);
            return "扇区布局已保存 → breakfront/sectors.json（" + layout.zoneCount() + " 据点）";
        } catch (IOException e) {
            return "保存失败：" + e;
        }
    }

    /** /bfs load：从磁盘重载布局（放弃未保存改动）。 */
    public String loadLayout(MinecraftServer server) {
        Path file = runDir.resolve("breakfront/sectors.json");
        if (!Files.isRegularFile(file)) {
            return "还没有已保存的布局文件（先 /bfs save）";
        }
        try {
            SectorLayout loaded = SectorLayout.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (loaded.zoneCount() == 0) {
                return "文件为空布局，拒绝装载";
            }
            layout = loaded;
            editorSectorIdx = 0;
            pushEditorPreview(server);
            return "已从磁盘装载：" + layout.toText();
        } catch (Exception e) {
            return "装载失败（文件损坏？）：" + e.getMessage();
        }
    }

    /** /bfs list：文本概览。 */
    public String editorLayoutText() {
        ensureEditorSector();
        StringBuilder sb = new StringBuilder("当前布局（共 " + layout.sectorCount() + " 扇区 / "
                + layout.zoneCount() + " 据点）：");
        for (int si = 0; si < layout.sectors().size(); si++) {
            SectorLayout.SectorDef def = layout.sectors().get(si);
            sb.append('\n').append(si == editorSectorIdx ? "→ " : "  ")
                    .append(si + 1).append(". ").append(def.name());
            for (SectorLayout.Zone z : def.zones()) {
                sb.append("\n     ").append(z.id())
                        .append(" @ (x=").append(String.format("%.1f", z.x()))
                        .append(", z=").append(String.format("%.1f", z.z()))
                        .append(", r=").append(String.format("%.0f", z.radius()))
                        .append(')');
            }
        }
        return sb.toString();
    }

    /** 给所有编辑器观看者推送最新预览（enabled=true）。 */
    public void pushEditorPreview(MinecraftServer server) {
        if (editorViewers.isEmpty() || server == null) {
            return;
        }
        ServerWorld world = server.getOverworld();
        var zones = new ArrayList<SectorEditPayload.ZoneView>();
        for (int si = 0; si < layout.sectors().size(); si++) {
            for (SectorLayout.Zone z : layout.sectors().get(si).zones()) {
                int cx = (int) z.x();
                int cz = (int) z.z();
                int topY = world.getTopY(Heightmap.Type.WORLD_SURFACE, cx, cz);
                double groundY = topY <= world.getBottomY() ? 64.0 : topY + 1.0;
                zones.add(new SectorEditPayload.ZoneView(z.id(), si, z.x(), groundY, z.z(), z.radius()));
            }
        }
        SectorEditPayload payload = new SectorEditPayload(true,
                Math.max(0, editorSectorIdx), zones);
        for (UUID viewerId : new ArrayList<>(editorViewers)) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(viewerId);
            if (p != null) {
                ServerPlayNetworking.send(p, payload);
            } else {
                editorViewers.remove(viewerId);
            }
        }
    }
}
