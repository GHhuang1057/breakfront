package com.breakfront.server;

import com.breakfront.game.BreakthroughGame;
import com.breakfront.game.MatchPhase;
import com.breakfront.game.Sector;
import com.breakfront.game.Side;
import com.breakfront.game.ZoneState;
import com.breakfront.net.MatchStatePayload;
import com.breakfront.net.ScoreboardPayload;
import com.breakfront.server.arena.ArenaViaduct;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端对局运行时：把纯逻辑 BreakthroughGame 与真实玩家/世界接起来。
 *
 * - 每 tick 调用 game.tick(0.05)
 * - 按 ZoneAnchor 统计圈内双方人数并喂给占点逻辑
 * - 默认竞技场（打靶房占位）：2 扇区 3 据点，坐标贴近原点便于摆桩验证
 */
public final class ServerMatch {

    private final TeamManager teams = new TeamManager();
    private final ScoreKeeper score = new ScoreKeeper();
    private final NpcSquad npc = new NpcSquad();
    private final BreakthroughGame game;
    private final Map<String, ZoneAnchor> anchors = new LinkedHashMap<>();
    private final List<String> zoneOrder = new ArrayList<>();
    private int syncCounter = 0; // 状态广播节流：每 10 tick 一次
    private boolean visualsPlaced = false; // 据点空间标识（信标）只放一次
    private boolean arenaBuilt = false;    // 竞技场城市只建一次
    private boolean arenaSkipped = false;  // 使用外部世界时跳过自建城市
    private boolean envFixed = false;      // 环境锁定只做一次（白昼/禁刷怪/禁天气）

    /** 回合自动循环：结算展示 8 秒后自动重开下一局。 */
    private static final double ROUND_END_PAUSE = 8.0;
    private double endPause = -1;

    // ---- 出生/赛程（S1）----
    private boolean autostart;          // 大厅人数达标自动开局
    private double lobbyTimer = -1;
    private MatchPhase lastPhase = MatchPhase.LOBBY;
    private final double[] attackerSpawn = {Double.NaN, Double.NaN}; // override {x,z}
    private final double[] defenderSpawn = {Double.NaN, Double.NaN};

    // ---- 单机训练模式（W4B：单人世界 = 自动 AI 对战）----
    private boolean training;          // 当前为单机训练（isSingleplayer）
    private boolean trainingArmed;     // AI 已刷齐、等待开赛
    private double trainingTimer = -1;

    public ServerMatch() {
        List<Sector> sectors = defaultSectors();
        this.game = new BreakthroughGame(sectors);
        indexAnchors();
    }

    private List<Sector> defaultSectors() {
        double captureSeconds = com.breakfront.game.BreakthroughTuning.DEFAULT_ZONE_CAPTURE_SECONDS;
        return List.of(
                new Sector("s1", "扇区一", List.of(
                        new ZoneState("A1", captureSeconds),
                        new ZoneState("A2", captureSeconds))),
                new Sector("s2", "扇区二", List.of(
                        new ZoneState("B1", captureSeconds))));
    }

    private void indexAnchors() {
        anchors.clear();
        zoneOrder.clear();
        // 与 viaduct 地图对齐：A1/A2 在高架东西两段，B1 在中央广场（见 arena 装载）
        double[][] spots = {
                {20.5, 24.5}, {76.5, 24.5}, {43.5, 51.5}
        };
        int i = 0;
        for (Sector sector : game.sectors()) {
            for (ZoneState zone : sector.zones()) {
                anchors.put(zone.id(), new ZoneAnchor(zone.id(), spots[i][0], spots[i][1], 6.0));
                zoneOrder.add(zone.id());
                i++;
            }
        }
    }

    /** 服务端主循环适配（20tps × 0.05s）。 */
    public void tick(MinecraftServer server) {
        // 单机（单人世界）= 训练模式：不建自建城，直接用玩家自己的世界
        if (server.isSingleplayer() && !arenaSkipped) {
            arenaSkipped = true;
            arenaBuilt = true;
        }
        if (!arenaBuilt && !arenaSkipped) {
            // 服务器目录放 breakfront.map.external 标记 => 使用外部世界地图，跳过自建城市
            try {
                Path flag = server.getRunDirectory().resolve("breakfront.map.external");
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
                BreakfrontServer.LOGGER.info("[Breakfront] viaduct arena built ({} zones)", zoneCountLog());
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
        // S1 赛程：大厅自动开局 + 进入 BATTLE 的上升沿做全员部署传送
        tickTraining(server);
        tickAutoStart(server);
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
    public void recordBotKill(net.minecraft.server.network.ServerPlayerEntity killer, int botSideOrd) {
        Side ks = teams.sideOf(killer.getUuid());
        score.creditKill(killer.getUuid(), killer.getGameProfile().getName(),
                ks == null ? -1 : ks.ordinal());
    }

    /** 记录一笔击杀（第 1 层 vanilla 事件调用；爆头标记由第 2 层富化后补录）。 */
    public void recordKill(net.minecraft.server.network.ServerPlayerEntity victim,
                           net.minecraft.entity.LivingEntity killer) {
        Side vs = teams.sideOf(victim.getUuid());
        int vSide = vs == null ? 1 : vs.ordinal();
        if (killer instanceof net.minecraft.server.network.ServerPlayerEntity kp) {
            Side ks = teams.sideOf(kp.getUuid());
            int kSide = ks == null ? -1 : ks.ordinal();
            score.record(victim.getUuid(), victim.getGameProfile().getName(), vSide,
                    kp.getUuid(), kp.getGameProfile().getName(), kSide, false);
        } else {
            score.record(victim.getUuid(), victim.getGameProfile().getName(), vSide,
                    null, null, -1, false);
        }
    }

    // ================= S1 出生 / 赛程 =================

    /** 单机训练模式：单人世界自动刷双方 AI 并开赛。 */
    private void tickTraining(MinecraftServer server) {
        if (!server.isSingleplayer()) {
            if (training) {
                training = false;
                trainingArmed = false;
                trainingTimer = -1;
            }
            return;
        }
        if (game.phase() == MatchPhase.ROUND_END) {
            return; // 结算展示中（自动重开由回合循环负责）
        }
        int players = server.getPlayerManager().getPlayerList().size();
        if (game.phase() == MatchPhase.LOBBY && players == 0) {
            training = false;
            trainingArmed = false;
            trainingTimer = -1;
            return;
        }
        if (!training) {
            training = true;
            BreakfrontServer.LOGGER.info("[Breakfront] 单机训练模式激活");
        }
        if (game.phase() == MatchPhase.BATTLE || game.phase() == MatchPhase.COUNTDOWN) {
            return; // 已开赛，交给回合/死亡逻辑
        }
        if (!trainingArmed) {
            // 玩家默认进攻方；补足双方 AI 小队（6 攻含真人 + 8 守）
            npc.setTarget(Side.ATTACKER, 6);
            npc.setTarget(Side.DEFENDER, 8);
            npc.topUp(this, server);
            trainingArmed = true;
            trainingTimer = 5;
            BreakfrontServer.LOGGER.info("[Breakfront] training squads ready (6v8 AI), round in 5s");
        } else {
            trainingTimer -= 0.05;
            if (trainingTimer <= 0) {
                trainingTimer = -1;
                beginRound();
                visualsPlaced = false;
                teleportAllToSpawns(server);
                BreakfrontServer.LOGGER.info("[Breakfront] training round begun");
            }
        }
    }

    private void tickAutoStart(MinecraftServer server) {
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

    public boolean autostartEnabled() {
        return autostart;
    }

    public void setAutostart(boolean on) {
        autostart = on;
        if (!on) {
            lobbyTimer = -1;
        }
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

    private int zoneCountLog() {
        return anchors.size();
    }
}
