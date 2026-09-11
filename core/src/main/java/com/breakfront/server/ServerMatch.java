package com.breakfront.server;

import com.breakfront.game.BreakthroughGame;
import com.breakfront.game.BreakthroughTuning;
import com.breakfront.game.MatchPhase;
import com.breakfront.game.MatchResult;
import com.breakfront.game.Sector;
import com.breakfront.game.Side;
import com.breakfront.game.ZoneState;
import com.breakfront.net.SectorEditPayload;
import com.breakfront.map.SectorLayout;
import com.breakfront.net.MatchStatePayload;
import com.breakfront.net.PlayerPosPayload;
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
import java.util.HashMap;
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

    /**
     * 自动填充每边目标上限（真人 + AI）。实际值由机器负载动态决定：
     * 负载好 → 趋向上限；负载差 → 下调（见 pickFillTarget）。
     */
    private static final int FILL_CAP = 16;
    /** 负载最差时的保底人数（每边，含真人）。 */
    private static final int FILL_FLOOR = 4;

    private final TeamManager teams = new TeamManager();
    private final ScoreKeeper score = new ScoreKeeper();
    /**
     * AI 战场小队（假玩家壳）。
     *
     * <p>2026-09-10 v2：旧的僵尸壳 {@code NpcSquad} 已**整体移除**（它按
     * 「目标 - 在线」补员，无真人时会无限补员 —— 实测 alive 涨到 755，直接把 TPS 拖垮）。
     * 现全部走本小队：大厅维持「非战斗 BOT」编制，真人进服即时热顶替。
     */
    private final BotSquad bots = new BotSquad();
    private final Map<String, ZoneAnchor> anchors = new LinkedHashMap<>();
    /** 玩家选定的下一重生点：zoneId / "base" / "observe"（一次消费，详见 setDeployChoice）。 */
    private final Map<UUID, String> deployChoices = new HashMap<>();
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
    /**
     * BF 出生点（大厅 / 进服落点）override {x,z}；NaN = 用世界出生点。
     *
     * <p>为什么需要它：据点（sectors.json）是**按地图**划的，换图后旧坐标全部落在
     * 未生成区块 → {@link #spawnFor} 一路兜底往外扫，实测把玩家扔到世界出生点外
     * 94 格、y=4 的深坑里（2026-09-10 崩溃日志："Level spawn (-2083,32,1371)
     * vs 玩家 (-2177.28, 4.00, 1417.15)"）。
     * 大厅阶段统一落 BF 出生点，可保证「进服即在出生点」，与据点配置是否过期无关。
     */
    private final double[] lobbySpawn = {Double.NaN, Double.NaN};
    /**
     * 管理员是否**手动接管**了 AI 编制（/bf bot trial 或 /bf npc add）。
     * true 时空服自愈不介入，避免把调试用的 BOT 秒清；/bf bot clear 交还给自动管理。
     */
    private boolean botManual;

    // ---- AI 自动填充（人机对战 / 单机=一真人其余AI）----
    private boolean autoFill = true;    // 默认开：有真人即按负载填充并自动开局
    private boolean autoArmed;          // bot 已补齐、等待开局
    private double autoTimer = -1;
    private int desiredPerSide = FILL_CAP; // 当前每边目标（真人+AI），按 TPS 动态
    private int adaptTick;                 // 填充目标节流计数

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
        bots.clearAll(server);
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
        if (syncCounter % 40 == 0) {
            rescueVoidedPlayers(server); // 真人落出世界（虚空）每 2s 救援
        }
        if (syncCounter % 20 == 0) {
            loginGateTick(server); // 登录门禁：未绑定账号宽限期后移出（每 1s）
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
        if (syncCounter % 5 == 0 && !server.getPlayerManager().getPlayerList().isEmpty()) {
            broadcastPos(server); // 每 0.25s 广播位置帧（雷达友军点）
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
        // 假玩家小队（AI BOT 重构的玩家壳实现）—— 放在阶段判定之前：
        // 非战斗阶段仍需执行阵亡清扫与状态维持，否则会累积"幽灵在线"的假玩家。
        bots.tick(this, server);
        if (game.phase() != MatchPhase.BATTLE) {
            return;
        }
        if (!visualsPlaced) {
            placeZoneVisuals(server.getOverworld());
            visualsPlaced = true;
        }
        var overworld = server.getOverworld();
        for (int idx = 0; idx < zoneOrder.size(); idx++) {
            ZoneAnchor anchor = anchors.get(zoneOrder.get(idx));
            int attackers = 0;
            int defenders = 0;
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (player.getWorld() != overworld || player.isSpectator()) {
                    continue;
                }
                Side side = sideOfEntity(player);
                if (side == null) {
                    continue;
                }
                if (anchor.contains(player.getX(), player.getZ())) {
                    if (side == Side.ATTACKER) {
                        attackers++;
                    } else {
                        defenders++;
                    }
                    // 领地判定可视化：站进判定方块时显示服务端判定用的锚点/半径，
                    // 用于现场核对客户端方形描边是否与判定一致（错位排查）
                    player.sendMessage(net.minecraft.text.Text.literal(String.format(
                            "§b◈ 占点判定 §f%s §7(x=%.0f, z=%.0f, r=%.0f)",
                            zoneOrder.get(idx), anchor.x(), anchor.z(), anchor.radius())), true);
                }
            }
            // 注：此处原有一段「僵尸壳 NPC 计入圈内人数」的实体扫描（tag=breakfront.npc）。
            // 僵尸壳已整体移除，AI 假玩家本身就是 ServerPlayerEntity，已在上面的玩家循环里
            // 按 sideOfEntity 计入，无需重复扫描 —— 且那段是「每个据点每 tick 一次
            // getEntitiesByClass(AABB)」，属于纯粹的每 tick 浪费。
            game.applyZonePresence(idx, attackers, defenders, 0.05);
        }
    }

    /**
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
     * 开局赛程：
     * - autoFill 开 & 大厅有真人 → 双阵营补齐至 16 并 5 秒后自动开局（单人=1 真人其余 AI）
     * - autoFill 关 → 沿用旧规则：双真实阵营就绪自动开（/bf autostart on）或 /bf start 手动开
     */
    private void tickAutoPlay(MinecraftServer server) {
        // 只统计**真人**：假玩家（AI bot）也在玩家列表里，若不排除会让填充逻辑
        // 误判"人已满"从而不再补员，且会把旧壳 NPC 又加回来与假玩家小队叠加。
        int humans = humanCount(server);

        // 空服自愈：一个真人都没有 → 复位到大厅初始状态（停局 / 清 BOT / 复位据点）。
        // 否则上一局的残局（倒计时、据点进度、票数、满地 BOT）会一直挂在那儿，
        // 下一个真人进来看到的是个进行中的残局而不是干净大厅。
        if (humans == 0) {
            resetWhenEmpty(server);
            return;
        }

        if (game.phase() != MatchPhase.LOBBY) {
            return; // 局中/倒计时/结算均不干预
        }
        if (autoFill) {
            if (!autoArmed) {
                desiredPerSide = pickFillTarget(server);
                applyFillTarget(server, desiredPerSide);
                autoArmed = true;
                autoTimer = 5.0;
                float tps = server.getAverageTickTime() <= 0 ? 20f : 1000f / server.getAverageTickTime();
                BreakfrontServer.LOGGER.info(
                        "[Breakfront] AI fill {}v{} ({} human, tps={}), round in 5s",
                        desiredPerSide, desiredPerSide, humans, String.format("%.0f", tps));
            } else {
                adaptTick++;
                if (adaptTick % 100 == 0) { // 大厅等待窗口内每 5s 按负载微调一次
                    int next = pickFillTarget(server);
                    if (next != desiredPerSide) {
                        desiredPerSide = next;
                        applyFillTarget(server, desiredPerSide);
                        BreakfrontServer.LOGGER.info("[Breakfront] AI fill adjusted to {}v{} (tps={})",
                                desiredPerSide, desiredPerSide,
                                String.format("%.0f", server.getAverageTickTime() <= 0 ? 20f
                                        : 1000f / server.getAverageTickTime()));
                    }
                }
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

    /** 当前**真人**数量（排除 AI 假玩家——它们同样出现在玩家列表里）。 */
    private int humanCount(MinecraftServer server) {
        int n = 0;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (!BotPlayerFactory.isBot(p)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 空服自愈：没有真人时把对局复位到大厅初始状态。
     *
     * <p>用户诉求（2026-09-10）：「服务器中无真人的时候自动重置到初始状态，有真人再开始对局」。
     * 复位内容 = 回大厅（清倒计时/票数/据点进度）+ 清空 AI 编制 + 清零填充目标 + 重置计时器。
     * 幂等：已经干净时直接返回，不会每 tick 反复刷日志。
     */
    private void resetWhenEmpty(MinecraftServer server) {
        // 管理员用 /bf bot trial|add 手动接管编制时，不自愈 —— 否则刚生成用于调试的
        // BOT 会被下一个 tick 立刻清掉（实测踩过）。交还自动管理用 /bf bot clear。
        if (botManual) {
            return;
        }
        boolean dirty = game.phase() != MatchPhase.LOBBY
                || bots.alive() > 0
                || autoArmed
                || desiredPerSide != 0
                || lobbyTimer >= 0;
        if (!dirty) {
            return;
        }
        game.returnToLobby();          // 复位据点进度 / 票数 / 倒计时
        score.reset();
        autoArmed = false;
        autoTimer = -1;
        lobbyTimer = -1;
        desiredPerSide = 0;
        bots.setTarget(Side.ATTACKER, 0);
        bots.setTarget(Side.DEFENDER, 0);
        bots.clearAll(server);
        visualsPlaced = false;
        broadcastState(server);
        BreakfrontServer.LOGGER.info("[Breakfront] 空服自愈：已复位到大厅初始状态（等真人进服再开局）");
    }

    /** 按当前 TPS 决定每边目标总人数：负载好趋上限，负载差保底。 */
    private int pickFillTarget(MinecraftServer server) {
        float ms = server.getAverageTickTime();
        float tps = ms <= 0 ? 20f : 1000f / ms;
        if (tps >= 19.4f) {
            return FILL_CAP;
        }
        if (tps >= 18.6f) {
            return (FILL_CAP + FILL_FLOOR) / 2;
        }
        return FILL_FLOOR;
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
    }

    /** 据点地表高度（信标底座所在 Y = 最高固体上方一格）。 */
    private double anchorGroundY(ServerWorld world, ZoneAnchor anchor) {
        return groundY(world, anchor.x(), anchor.z());
    }

    /** 据点锚点坐标摘要（供 /bf status 展示，方便传送验证）；无地形的锚点会标红提示。 */
    public String zoneAnchorsText() {
        StringBuilder sb = new StringBuilder();
        var srv = BreakfrontServer.server();
        ServerWorld world = srv == null ? null : srv.getOverworld();
        int gi = 0;
        for (String id : zoneOrder) {
            ZoneAnchor a = anchors.get(id);
            char letter = (gi >= 0 && gi < 26) ? (char) ('A' + gi) : '?';
            sb.append('\n').append(letter).append(" (").append(a.zoneId()).append(") @ (x=")
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
    }

    /** 竞技场环境锁定：恒定白昼雷暴（沉浸氛围）、禁刷怪/天气变化/生物破坏，并清空既有生物。 */
    private boolean fixArenaEnvironment(MinecraftServer server) {
        String[] cmds = {
                "gamerule doDaylightCycle false",
                "gamerule doWeatherCycle false",
                "gamerule doMobSpawning false",
                "gamerule mobGriefing false",
                "gamerule naturalRegeneration false",
                "time set 6000",
                "weather thunder 1000000",
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

    /** M8：管理员传送到某据点正上方（供可视化查验位置），命令文本传送规避映射面。 */
    public String adminGoto(MinecraftServer server, ServerPlayerEntity player, String zoneId) {
        if (player == null) {
            return "仅玩家可用";
        }
        if (server == null) {
            return "服务器未就绪";
        }
        ZoneAnchor a = anchors.get(zoneId);
        if (a == null) {
            return "据点不存在: " + zoneId + "（/bfs list 查看全部 id）";
        }
        ServerWorld world = server.getOverworld();
        double gy = groundY(world, a.x(), a.z());
        if (Double.isNaN(gy)) {
            return String.format("据点 %s 坐标 (x=%.0f, z=%.0f) 处无地形（落在未生成区块），"
                    + "请先在管理台按真实俯瞰图重划扇区", zoneId, a.x(), a.z());
        }
        double y = gy + 1.5;
        exec(server, String.format("tp %s %.1f %.1f %.1f",
                player.getGameProfile().getName(), a.x(), y, a.z()));
        return String.format("已传送至 %s（x=%.0f, z=%.0f, r=%.0f）",
                zoneId, a.x(), a.z(), a.radius());
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
                game.attackerTicketsMax(),
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

    /** 广播玩家位置帧（每 0.25s）：客户端雷达只显示同阵营队友，故**只发同侧的 rows**。
     *
     * <p>为什么要按接收者过滤：这是 32v32 下唯一由我们自造的高频广播。
     * 每行 ≈ 33 字节（name + side + x/z 两个 double + yaw + alive），
     * 32 人一帧 ≈ 1.0KB，4Hz × 32 个接收者 ≈ 135KB/s ≈ 1.08Mbps ——
     * 北京出口只有 5Mbps，光雷达帧就吃掉两成以上，而且是纯浪费：
     * 客户端本来就只画同阵营点（敌情靠目视/据点状态，不泄露位置）。
     * 过滤后带宽直接减半，且**线格式不变**，老客户端二进制兼容。
     */
    private void broadcastPos(MinecraftServer server) {
        var players = server.getPlayerManager().getPlayerList();
        // 先按阵营归拢一次，避免每个接收者都重算 sideOfEntity（那是标签查询，不便宜）
        java.util.Map<Integer, List<PlayerPosPayload.Row>> bySide = new java.util.HashMap<>();
        for (ServerPlayerEntity p : players) {
            // ⚠️ 必须走 sideOfEntity 而非 teams.sideOf：AI 假玩家（BotSquad）不在
            // TeamManager 里，只挂了 bf.side.att/def 命令标签；用 teams.sideOf 会让它们
            // 一律变成 -1，客户端据此就无法给 BOT 标友方/敌方（雷达、头顶菱形全会缺）。
            Side side = sideOfEntity(p);
            int s = side == null ? -1 : side.ordinal();
            bySide.computeIfAbsent(s, k -> new ArrayList<>())
                    .add(new PlayerPosPayload.Row(p.getGameProfile().getName(), s,
                            p.getX(), p.getZ(), p.getYaw(), p.isAlive() && p.getHealth() > 0));
        }
        for (ServerPlayerEntity player : players) {
            Side side = sideOfEntity(player);
            int s = side == null ? -1 : side.ordinal();
            List<PlayerPosPayload.Row> rows = bySide.get(s);
            if (rows == null || rows.isEmpty()) {
                continue;
            }
            ServerPlayNetworking.send(player, new PlayerPosPayload(rows));
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

    /** 开局统一入口：重开状态机、清战绩、重置 AI 编制、复位据点标识、全员发装备。 */
    public void beginRound() {
        game.startRound();
        score.reset();
        visualsPlaced = false;
        MinecraftServer server = BreakfrontServer.server();
        bots.clearAll(server);          // 新回合：AI 编制归零，随后按对账在新出生点重建
        if (server != null) {
            // 据点区块常驻加载（管理台可能刚改过扇区，锚点变了也要覆盖新坐标）。
            // 不加载则 groundY 读盘失败 → NaN → 占领/票数/HUD 全停（2026-09-11 实测）。
            try {
                forceLoadAnchorChunks(server);
            } catch (Exception e) {
                BreakfrontServer.LOGGER.warn("[Breakfront] forceLoadAnchorChunks failed: {}", e.toString());
            }
            // ⚠️ 开赛前**必须**把填充目标落实一遍：`/bf start` 手动开局不会走
            //    tickAutoPlay 的 applyFillTarget 分支，目标人数还停在 0 →
            //    结果新回合里一个 BOT 都没有（用户反馈「开局后 AI BOT 没出现」即此路径）。
            if (autoFill && humanCount(server) > 0) {
                desiredPerSide = pickFillTarget(server);
                bots.setTarget(Side.ATTACKER, desiredPerSide);
                bots.setTarget(Side.DEFENDER, desiredPerSide);
            }
            bots.reconcile(this, server);
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                // ⚠️ 必须跳过 AI 假玩家：它们不在 TeamManager 里，giveKit 会走
                //    classOf/gunIdOf 的兜底分支 → 被强行发成**突击兵默认套件**
                //    （hk416d + 556x45），把 BOT 自己的兵种枪覆盖掉，还配上不匹配的弹药。
                //    BOT 的装备由 BotSquad.applyLoadout 负责（含备弹）。
                if (BotPlayerFactory.isBot(p)) {
                    continue;
                }
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
    }

    /** 兵种装备发放（进服/开局/重生共用入口）。 */
    public void kitPlayer(MinecraftServer server, ServerPlayerEntity player) {
        try {
            Kits.giveKit(this, server, player);
        } catch (Throwable t) {
            BreakfrontServer.LOGGER.warn("[Breakfront] kit issue for {}: {}",
                    player.getName().getString(), t.toString());
        }
    }

    public ScoreKeeper score() {
        return score;
    }

    /** 假玩家小队（AI BOT 的玩家壳实现；大厅「非战斗 BOT」与真人热顶替都在这里）。 */
    public BotSquad bots() {
        return bots;
    }

    /**
     * 标记 / 解除「管理员手动接管 AI 编制」。
     *
     * <p>{@code true}（{@code /bf bot trial}、{@code /bf npc add}）：空服自愈不介入，
     * 否则刚生成用于调试的 BOT 会被下一个 tick 立刻清掉。
     * <p>{@code false}（{@code /bf bot clear}）：交还大厅赛程自动管理。
     */
    public void setBotManual(boolean on) {
        this.botManual = on;
    }

    /** 出生点公开坐标（出生 y 已含地面）；供 AI 小队与外部工具复用。 */
    public double[] spawnsFor(Side side, ServerWorld world) {
        return spawnFor(side, world);
    }

    public int zoneIndex(String zoneId) {
        return zoneOrder.indexOf(zoneId);
    }

    // ---------- 登录门禁（正版/离线一律要求 Geekhonize 账号绑定） ----------

    /** 宽限期（毫秒）：进服后未绑定账号允许的登录窗口。 */
    public static final long LOGIN_GRACE_MS = 90_000;
    /** 每玩家宽限起点（绑定后移除；退服清理）。 */
    private final java.util.Map<java.util.UUID, Long> loginGrace = new java.util.HashMap<>();

    /** 每 1s 巡检：未绑定者 actionbar 倒计时；到期 disconnect。op(≥2 级) 与 AI 假玩家豁免。 */
    private void loginGateTick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        // ⚠️ 必须用**快照**迭代：下面的 disconnect → PlayerManager.remove 会改写
        // playerList，直接 for-each 该列表会抛 ConcurrentModificationException 并打崩
        // 整个 server tick 循环（表现为服务端崩溃重启、场上实体全清）。
        // 实测崩溃：crash-2026-09-10_13.33.49-server.txt，栈顶即本方法第 644 行。
        List<ServerPlayerEntity> snapshot = new ArrayList<>(server.getPlayerManager().getPlayerList());
        for (ServerPlayerEntity p : snapshot) {
            // AI 假玩家豁免：它们没有真实客户端，无法完成 Geekhonize 登录；
            // 不豁免则会在 90s 宽限到期后被集体踢出（并触发上述崩溃 → bot 全部消失）。
            if (BotPlayerFactory.isBot(p)) {
                loginGrace.remove(p.getUuid());
                continue;
            }
            if (geoBinds.containsKey(p.getUuid())) {
                loginGrace.remove(p.getUuid());
                continue;
            }
            if (p.hasPermissionLevel(2)) {
                continue; // 管理员豁免
            }
            long start = loginGrace.computeIfAbsent(p.getUuid(), k -> now);
            long leftMs = LOGIN_GRACE_MS - (now - start);
            if (leftMs <= 0) {
                loginGrace.remove(p.getUuid());
                if (p.networkHandler != null) {
                    BreakfrontServer.LOGGER.info("[Breakfront] login gate: kicking unbound player {}",
                            p.getName().getString());
                    p.networkHandler.disconnect(net.minecraft.text.Text.literal(
                            "请先登录 Geekhonize 账号再进服：官方启动器登录后自动绑定；"
                                    + "PCL/FCL 等第三方启动器请在游戏内 Esc → GEEKHONIZE 账号 → 浏览器登录"));
                }
                continue;
            }
            long leftSec = leftMs / 1000 + 1;
            if (leftSec % 10 == 0 || leftSec <= 10) {
                p.sendMessage(net.minecraft.text.Text.literal(
                        "§e[BF] 请在 " + leftSec + " 秒内登录 Geekhonize 账号（GEEKHONIZE 账号界面，"
                                + "支持浏览器登录），否则将被移出服务器"), true);
            }
        }
        loginGrace.keySet().removeIf(id -> server.getPlayerManager().getPlayer(id) == null);
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

    /** 据点半径（半边长），序号越界返回 8.0 兜底。 */
    public double zoneRadius(int globalIndex) {
        if (globalIndex < 0 || globalIndex >= zoneOrder.size()) {
            return 8.0;
        }
        ZoneAnchor a = anchors.get(zoneOrder.get(globalIndex));
        return a == null ? 8.0 : a.radius();
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

    /** 玩家进服：套用 100HP 战斗模型、自动补位；战局中直接部署到出生区并钉重生点。 */
    public void onPlayerJoin(MinecraftServer server, ServerPlayerEntity player) {
        applyCombatModel(player);
        // ⚠️⚠️ AI 假玩家也是「玩家连接」，会走同一个 JOIN 事件。这里必须**早返回**：
        //   对账会生成新 BOT，新 BOT 又触发 JOIN → 对账 → …… 无限递归。
        //   实测后果：一次 /bf bot trial 3 打出 804 次生成 / 817 次 join / 799 次移除，
        //   服务端 tick 落后 40+，RCON 直接超时。
        //   假玩家的出装/站位由 BotSquad.spawn/applyLoadout 自己负责，无需走真人流程。
        if (BotPlayerFactory.isBot(player)) {
            return;
        }
        // 取消旁观后的自愈：旧客户端「观察」写入 playerdata 的 gamemode=spectator
        // 会跨重启保留，而新逻辑不再有任何退出旁观的路径 → 玩家永远卡旁观。
        // 入服即强制回生存（2026-09-11 用户实测卡旁观）。
        if (player.isSpectator()) {
            exec(server, "gamemode survival " + player.getGameProfile().getName());
        }
        if (teams.sideOf(player.getUuid()) == null) {
            teams.assignLeast(player.getUuid());
        }
        // 真人入编 → **立刻**对账：该侧（真人+BOT）超编时移除一个 BOT，真人直接接管它的名额。
        // 放在部署之前，保证 BOT 让出的位置不会被下一轮补员抢回去。
        bots.reconcile(this, server);
        // 任意阶段进服都部署到己方出生区（spawnFor 保证落真实实体表面；不再有假高度二次传送）
        deployPlayer(server, player);
        kitPlayer(server, player);
    }

    /** 战斗数值模型：真人满血 = 100（原版 20 心的 ×5 细化粒度）；基础值只设一次。 */
    private void applyCombatModel(ServerPlayerEntity player) {
        var maxHealth = player.getAttributeInstance(
                net.minecraft.entity.attribute.EntityAttributes.GENERIC_MAX_HEALTH);
        if (maxHealth != null
                && Math.abs(maxHealth.getBaseValue() - BreakthroughTuning.PLAYER_MAX_HEALTH) > 1e-3) {
            maxHealth.setBaseValue(BreakthroughTuning.PLAYER_MAX_HEALTH);
        }
        player.setHealth((float) BreakthroughTuning.PLAYER_MAX_HEALTH);
    }

    public void onPlayerLeft(UUID playerId) {
        teams.leave(playerId);
        editorViewers.remove(playerId);
        unbindGeo(playerId); // Geekhonize 绑定随退服释放，避免残留绑定误判同账号顶号
    }

    /** 玩家死亡：任意阶段都把重生点钉在己方部署区（vanilla 复活即回防线/出生区，
     *  绝不落到世界原版出生点——低海拔地图的世界出生点可能悬空/虚空）。
     *  若玩家此前已通过 /bf deploy 选定了合法重生点（zoneId/base），则钉到所选锚点，随后清除选择。 */
    public void onPlayerDied(MinecraftServer server, ServerPlayerEntity player) {
        Side side = teams.sideOf(player.getUuid());
        if (side == null) {
            return;
        }
        String choice = deployChoices.remove(player.getUuid());
        double[] sp;
        if (choice != null && !choice.equals("observe")) {
            double[] z = deploySpawn(side, server.getOverworld(), choice);
            sp = (z != null) ? z : spawnFor(side, server.getOverworld());
        } else {
            sp = spawnFor(side, server.getOverworld());
        }
        exec(server, String.format("spawnpoint %s %d %d %d",
                player.getGameProfile().getName(), (int) Math.floor(sp[0]), (int) Math.floor(sp[1]), (int) Math.floor(sp[2])));
    }

    // ================= 部署点选择（任务 A：/bf deploy） =================

    /**
     * 记录玩家下一重生点选择并（若已阵亡）立即把重生点锚定到所选点。
     * 闭环：客户端部署屏点击「部署」→ 发 /bf deploy &lt;zoneId|base|observe&gt; →
     * 本方法设置 choice 并即时 spawnpoint（玩家此时已死）→ 客户端 requestRespawn()
     * 走 vanilla 重生落到所选点；一次消费后清除，避免污染下次死亡。
     */
    public void setDeployChoice(MinecraftServer server, ServerPlayerEntity player, String choice) {
        Side side = teams.sideOf(player.getUuid());
        if (side == null || player.getServer() == null) {
            return;
        }
        String resolved = resolveDeployChoice(side, choice);
        if (resolved == null) {
            resolved = "base"; // 非法目标回退己方出生区
        }
        if (resolved.equals("observe")) {
            // 2026-09-11 取消旁观模式：observe 选择一律落到己方出生区（保留字符串兼容旧客户端）
            resolved = "base";
        }
        deployChoices.put(player.getUuid(), resolved);
        // 阵亡（部署页已开）时立即锚定，保证 requestRespawn 落点正确；随后消费清除
        if (!player.isAlive() || player.getHealth() <= 0f) {
            double[] sp = deploySpawn(side, server.getOverworld(), resolved);
            if (sp == null) {
                sp = spawnFor(side, server.getOverworld());
            }
            exec(server, String.format("spawnpoint %s %d %d %d",
                    player.getGameProfile().getName(), (int) Math.floor(sp[0]), (int) Math.floor(sp[1]), (int) Math.floor(sp[2])));
            deployChoices.remove(player.getUuid());
        }
    }

    /** 校验部署目标对本方是否合法；不合法返回 null。 */
    public String resolveDeployChoice(Side side, String choice) {
        if (choice == null) {
            return null;
        }
        String c = choice.trim().toLowerCase();
        if (c.equals("base") || c.equals("observe")) {
            return c;
        }
        ZoneState z = findZone(c);
        if (z == null) {
            return null;
        }
        // 争夺 = 守方名下且推进度>0（前线正在打）；攻方已占区 owner=ATTACKER 且 meter 恒为 1.0（稳固）
        boolean contested = (z.owner() == Side.DEFENDER && z.meter() > 1e-3);
        boolean mine = (side == Side.ATTACKER)
                ? (z.owner() == Side.ATTACKER)                 // 攻方：已控制的据点
                : (z.owner() == Side.DEFENDER && !contested);  // 守方：稳固防守点（非争夺）
        return mine ? c : null;
    }

    /** 出生坐标（含 Y）：base = 己方出生区；zoneId = 该据点内侧安全站面。 */
    public double[] deploySpawn(Side side, ServerWorld world, String choice) {
        if (choice == null || choice.equals("base")) {
            return spawnFor(side, world);
        }
        return zoneSpawn(side, world, choice);
    }

    /** 据点内侧（偏向己方防线一侧）安全站面；anchor 不存在返回 null。 */
    private double[] zoneSpawn(Side side, ServerWorld world, String zoneId) {
        ZoneAnchor a = anchors.get(zoneId);
        if (a == null) {
            return null;
        }
        double ox = (side == Side.ATTACKER ? -1 : 1) * (a.radius() * 0.4);
        return surfaceLanding(world, a.x() + ox, a.z(), a);
    }

    /** 在当前扇区按 id 查找据点状态（部署点只来自当前扇区）。 */
    private ZoneState findZone(String id) {
        for (ZoneState z : game.currentSector().zones()) {
            if (z.id().equals(id)) {
                return z;
            }
        }
        return null;
    }

    /** 清除某玩家的待消费部署选择（重生后清理，避免污染下次死亡）。 */
    public void clearDeployChoice(UUID id) {
        deployChoices.remove(id);
    }

    private void deployPlayer(MinecraftServer server, ServerPlayerEntity player) {
        Side side = teams.sideOf(player.getUuid());
        if (side == null) {
            return;
        }
        // 大厅阶段：全员落在 BF 出生点（与世界出生点一致，除非 spawn.lobby 覆盖）。
        // 战斗阶段才用阵营出生区 —— 这样即使 sectors.json 还是上一张图的坐标，
        // 玩家进服也稳定在出生点，而不是被兜底逻辑扔到几十格外的深坑里。
        double[] sp = game.phase() == MatchPhase.LOBBY
                ? lobbySpawnPoint(server.getOverworld())
                : spawnFor(side, server.getOverworld());
        exec(server, String.format("tp %s %.1f %.1f %.1f",
                player.getGameProfile().getName(), sp[0], sp[1], sp[2]));
        exec(server, String.format("spawnpoint %s %d %d %d",
                player.getGameProfile().getName(), (int) Math.floor(sp[0]), (int) Math.floor(sp[1]), (int) Math.floor(sp[2])));
    }

    /** 玩家最后被救援时间戳（防抖，避免下坠途中反复瞬移）。 */
    private final java.util.Map<java.util.UUID, Long> lastRescueAt = new java.util.HashMap<>();

    /** Geekhonize 账号绑定：UUID -> [username, rolesCsv]（离线服防自报名冒名）。 */
    private final java.util.Map<java.util.UUID, String[]> geoBinds = new java.util.HashMap<>();

    /** 掉出世界救援（2026-09-05 v3 根治版）：只认「低于世界建造底部 +2」的真·掉出世界。
     *  判据 = getBottomY()+2（1.21.1 = -62）——平坦/低海拔地图的地面（如 y≈-60 超级平坦层）
     *  完全不受影响，任何真实实体表面行走都不会被拉回（此前写死 y<-10 把低海拔地图当虚空，
     *  导致用户在正常地面上每 2-3s 被瞬移回出生点，即「走路被拉回原位」）。 */
    private void rescueVoidedPlayers(MinecraftServer server) {
        ServerWorld overworld = server.getOverworld();
        double voidFloor = overworld.getBottomY() + 2.0; // -62：低于此必然已掉出可建造世界
        long now = System.currentTimeMillis();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p.isSpectator() || p.getY() > voidFloor) {
                continue;
            }
            Long last = lastRescueAt.get(p.getUuid());
            if (last != null && now - last < 3000) {
                continue;
            }
            lastRescueAt.put(p.getUuid(), now);
            Side side = teams.sideOf(p.getUuid());
            if (side == null) {
                teams.assignLeast(p.getUuid());
                side = teams.sideOf(p.getUuid());
            }
            double[] sp = spawnFor(side, overworld);
            exec(server, String.format("tp %s %.1f %.1f %.1f",
                    p.getGameProfile().getName(), sp[0], sp[1], sp[2]));
            BreakfrontServer.LOGGER.info("[Breakfront] rescued {} from out-of-world void -> ({},{},{})",
                    p.getName().getString(), String.format("%.1f", sp[0]),
                    String.format("%.1f", sp[1]), String.format("%.1f", sp[2]));
        }
    }

    private void teleportAllToSpawns(MinecraftServer server) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            Side side = teams.sideOf(p.getUuid());
            if (side != null) {
                deployPlayer(server, p);
            }
        }
    }

    /**
     * BF 出生点的实际落点 {x,y,z}（真实实体表面，y 已 +1）。
     *
     * <p>取 override（{@code spawn.lobby}），没有则用世界出生点；若该柱无地形
     * （世界出生点可能落在未生成区块），由近及远找最近有地面的柱，绝不返回架空高度。
     */
    public double[] lobbySpawnPoint(ServerWorld world) {
        double x, z;
        int preferY;
        if (!Double.isNaN(lobbySpawn[0])) {
            x = lobbySpawn[0];
            z = lobbySpawn[1];
            double t0 = columnTopY(world, x, z);
            preferY = Double.isNaN(t0) ? 80 : (int) Math.floor(t0) + 1;
        } else {
            var sp = world.getSpawnPos();
            x = sp.getX() + 0.5;
            z = sp.getZ() + 0.5;
            // ⚠️ 世界出生点自带的 Y 才是"出生点"：Metro 实测出生点 y=32 是合法站位
            //    （y-1 实心、y 与 y+1 空），而高度图柱顶是 76 —— 多盖了一层结构。
            //    若无条件用柱顶，玩家会被放到出生点上方 46 格的屋顶上（2026-09-10 实测）。
            preferY = sp.getY();
        }
        if (standableAt(world, x, preferY, z)) {
            return new double[]{x, preferY, z};
        }
        double t = columnTopY(world, x, z);
        if (!Double.isNaN(t)) {
            int ty = (int) Math.floor(t) + 1;
            for (int d = 0; d <= 6; d++) {
                if (standableAt(world, x, ty + d, z)) {
                    return new double[]{x, ty + d, z};
                }
                if (standableAt(world, x, ty - d, z)) {
                    return new double[]{x, ty - d, z};
                }
            }
            return new double[]{x, ty, z};
        }
        // 出生点柱没有地形：由近及远八向找最近安全站位（不然玩家会掉进虚空）
        double[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        for (int d = 4; d <= 128; d += 4) {
            for (double[] dir : dirs) {
                double cx = x + dir[0] * d;
                double cz = z + dir[1] * d;
                double ct = columnTopY(world, cx, cz);
                if (!Double.isNaN(ct)) {
                    int cty = (int) Math.floor(ct) + 1;
                    if (standableAt(world, cx, cty, cz)) {
                        BreakfrontServer.LOGGER.warn(
                                "[Breakfront] 出生点 ({}, {}) 无地形，已外移到 ({}, {})",
                                (long) x, (long) z, (long) cx, (long) cz);
                        return new double[]{cx, cty, cz};
                    }
                }
            }
        }
        return new double[]{x, 80, z};
    }

    /**
     * (x, y, z) 是否是能站人的位置：脚下有碰撞体（踩得住），且身体两格是空的（站得下）。
     *
     * <p>用 {@code getCollisionShape} 判据而非高度图：高度图只给"最高阻挡面"，
     * 在多层结构（Metro 这类有上下层的地图）里会把出生点算到屋顶上。
     */
    private static boolean standableAt(ServerWorld world, double x, int y, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        if (y <= world.getBottomY() || y >= world.getTopY()) {
            return false;
        }
        var mut = new net.minecraft.util.math.BlockPos.Mutable(bx, y, bz);
        // 脚下方块必须有碰撞体
        if (world.getBlockState(mut.set(bx, y - 1, bz)).getCollisionShape(world, mut).isEmpty()) {
            return false;
        }
        // 身体两格必须无碰撞（避免卡进方块/窒息）
        if (!world.getBlockState(mut.set(bx, y, bz)).getCollisionShape(world, mut).isEmpty()) {
            return false;
        }
        return world.getBlockState(mut.set(bx, y + 1, bz)).getCollisionShape(world, mut).isEmpty();
    }

    /**
     * 预加载并常驻出生点周围区块（原版 {@code /forceload}）。
     *
     * <p>为什么必须做：出生点周边若没被加载/生成，{@code columnTopY} 返回 NaN，
     * 出生逻辑就会被迫向外兜底 → 玩家不在出生点出生。常驻后出生点地形恒定可用。
     */
    public void forceLoadSpawnChunks(MinecraftServer server, int radiusBlocks) {
        double[] sp = lobbySpawnPoint(server.getOverworld());
        int x0 = (int) Math.floor(sp[0] - radiusBlocks);
        int z0 = (int) Math.floor(sp[2] - radiusBlocks);
        int x1 = (int) Math.floor(sp[0] + radiusBlocks);
        int z1 = (int) Math.floor(sp[2] + radiusBlocks);
        exec(server, String.format("forceload add %d %d %d %d", x0, z0, x1, z1));
        BreakfrontServer.LOGGER.info(
                "[Breakfront] 出生点 ({}, {}, {}) 已常驻加载 ±{} 格区块",
                (long) sp[0], (long) sp[1], (long) sp[2], radiusBlocks);
    }

    /** 据点锚点区块常驻加载。
     * ⚠️ 2026-09-11 实测定案：A1/A2 曾被 /bf status 判「无地形」——坐标其实没问题，
     * 根因是**发版重启后据点区块不驻留内存**：groundY 的同步读盘失败 → NaN →
     * 占领推进 / 信标 / HUD 扇区 / BOT 部署整条链路停摆（票数不动的直接原因）。
     * 与出生区块同待遇：服务器启动与每回合开始各 forceload 一次。 */
    public void forceLoadAnchorChunks(MinecraftServer server) {
        int n = 0;
        for (String id : zoneOrder) {
            ZoneAnchor a = anchors.get(id);
            if (a == null) continue;
            exec(server, String.format("forceload add %d %d",
                    (int) Math.floor(a.x()), (int) Math.floor(a.z())));
            n++;
        }
        if (n > 0) {
            BreakfrontServer.LOGGER.info("[Breakfront] {} 个据点锚点区块已常驻加载", n);
        }
    }

    /** 出生坐标（含 Y），攻/守默认锚定在首/末据点的阵营侧；可 /bf spawns set 覆盖。
     *  出生/重生落点一律经 {@link #surfaceLanding}：优先目标柱顶实体表面，
     *  兜底逐级找锚点/世界出生「有实体的站面」——玩家重生直接落在实体表面上，
     *  不再有虚空/假高度/被拉回。（2026-09-05 v4 定案） */
    /** 出生点兜底分离距离的**起始**值（格）：由近及远扫描，攻方取负向、守方取正向。
     *  只需保证攻守间距 > 索敌半径 34m，故 40 格起即可（实测世界已生成范围常很有限）。 */
    private static final double FALLBACK_SPAWN_SEPARATION = 40.0;

    private double[] spawnFor(Side side, ServerWorld world) {
        double[] ov = side == Side.ATTACKER ? attackerSpawn : defenderSpawn;
        int idx = side == Side.ATTACKER ? 0 : Math.max(0, zoneOrder.size() - 1);
        ZoneAnchor a = anchors.get(zoneOrder.get(idx));
        if (!Double.isNaN(ov[0])) {
            return surfaceLanding(world, ov[0], ov[1], a);
        }
        if (a != null) {
            double dir = side == Side.ATTACKER ? -1 : 1;
            double sx = a.x() + dir * (a.radius() + 5);
            // ⚠️ 先验证据点侧真有地面：sectors.json 的据点可能落在**未生成区块**，
            // 此时 surfaceLanding 会逐级兜底到「世界出生点」→ 攻守双方落到同一点 →
            // 出生即互相屠杀、无限死亡重生（实测表现即玩家看到的"AI 忽隐忽现"）。
            if (!Double.isNaN(columnTopY(world, sx, a.z()))) {
                return surfaceLanding(world, sx, a.z(), a);
            }
            BreakfrontServer.LOGGER.warn(
                    "[Breakfront] {} 的锚点据点 {} 处无地面（坐标落在未生成区块？），改用阵营分离兜底出生点",
                    side == Side.ATTACKER ? "攻方" : "守方", zoneOrder.get(idx));
        }
        return fallbackSpawn(world, side);
    }

    /**
     * 出生点兜底：以世界出生点为中心，**按阵营向两侧水平分离**。
     *
     * <p>修 2026-09-10 实测缺陷：攻守出生点若重合，bot 一出生就在彼此射程内，
     * 开局即互屠 → 无限死亡重生（观感即"忽隐忽现"）。这里沿 X 轴逐级外扩寻找
     * 有地面的柱（MOTION_BLOCKING 柱顶非 NaN），最多到 512 格；都找不到才退回世界出生点。
     */
    private double[] fallbackSpawn(ServerWorld world, Side side) {
        var sp = world.getSpawnPos();
        double bx = sp.getX() + 0.5;
        double bz = sp.getZ() + 0.5;
        double sign = side == Side.ATTACKER ? -1 : 1;
        // 由近及远、多方向探测：世界**已生成范围可能很小**（实测 ±128 起扫到 512
        // 全无地面 —— 出生点周边只有有限区块被生成），硬编码大距离起步会一路扫空
        // 又退回重合点。故从 40 格起、步长 8，并依次试 ±X / ±Z / 对角方向。
        double[][] dirs = {{sign, 0}, {0, sign}, {sign, sign}, {sign, -sign}};
        for (double d = FALLBACK_SPAWN_SEPARATION; d <= 512; d += 8) {
            for (double[] dir : dirs) {
                double tx = bx + dir[0] * d;
                double tz = bz + dir[1] * d;
                double t = columnTopY(world, tx, tz);
                if (Double.isNaN(t)) {
                    continue;
                }
                // 优先与「世界出生点同层」的安全站位（fallback 本就是围着世界出生点找的），
                // 其次才退到该柱高度图柱顶。否则在 Metro 这类多层地图上，
                // 柱顶会落在出生点上方的屋顶（实测 y=78 vs 出生点 y=32）。
                int y = safeYNear(world, tx, tz, sp.getY());
                if (y != Integer.MIN_VALUE) {
                    return new double[]{tx, y, tz};
                }
            }
        }
        return surfaceLanding(world, bx, bz, null);
    }

    /**
     * 在 (x, z) 找一个安全站位 y：先试 preferredY 及其 ±6 邻域，再退到该柱高度图柱顶。
     * 返回 {@link Integer#MIN_VALUE} 表示该柱没有任何可站位置。
     */
    private static int safeYNear(ServerWorld world, double x, double z, int preferredY) {
        for (int d = 0; d <= 6; d++) {
            if (standableAt(world, x, preferredY + d, z)) {
                return preferredY + d;
            }
            if (standableAt(world, x, preferredY - d, z)) {
                return preferredY - d;
            }
        }
        double t = columnTopY(world, x, z);
        if (!Double.isNaN(t)) {
            int ty = (int) Math.floor(t) + 1;
            if (standableAt(world, x, ty, z)) {
                return ty;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** 「实体表面落点」：依次取 目标柱顶 → 向锚点方向逐米最近有块柱 →
     *  锚点柱 → 世界出生点柱。每一级都是 MOTION_BLOCKING 真实柱顶（实体可站立面），
     *  绝不返回架空高度；全空仅剩世界级兜底用世界出生柱。 */
    private double[] surfaceLanding(ServerWorld world, double tx, double tz, ZoneAnchor anchor) {
        // 1) 目标柱顶
        double t = columnTopY(world, tx, tz);
        if (!Double.isNaN(t)) {
            return new double[]{tx, t + 1, tz};
        }
        // 2) 向锚点（或反向兜底原点）方向逐米找最近有块柱
        double ax = anchor == null ? 8 : anchor.x();
        double az = anchor == null ? 8 : anchor.z();
        double dx = ax - tx;
        double dz = az - tz;
        double len = Math.hypot(dx, dz);
        double ux = len > 1e-6 ? dx / len : 1;
        double uz = len > 1e-6 ? dz / len : 0;
        for (int step = 1; step <= 40; step++) {
            double cx = tx + ux * step;
            double cz = tz + uz * step;
            double ct = columnTopY(world, cx, cz);
            if (!Double.isNaN(ct)) {
                return new double[]{cx, ct + 1, cz};
            }
        }
        // 3) 锚点柱
        if (anchor != null) {
            double at = columnTopY(world, anchor.x(), anchor.z());
            if (!Double.isNaN(at)) {
                return new double[]{anchor.x(), at + 1, anchor.z()};
            }
        }
        // 4) 世界出生点柱（终级兜底，仍是实体站面）
        var sp = world.getSpawnPos();
        double st = columnTopY(world, sp.getX() + 0.5, sp.getZ() + 0.5);
        if (!Double.isNaN(st)) {
            return new double[]{sp.getX() + 0.5, st + 1, sp.getZ() + 0.5};
        }
        // 极极端：任意已加载区块第一柱
        double lt = columnTopY(world, 0.5, 0.5);
        return new double[]{0.5, Double.isNaN(lt) ? 80 : lt + 1, 0.5};
    }

    /** 柱顶方块 Y（方块顶面坐标）；该柱无方块（虚空）返回 NaN。 */
    private static double columnTopY(ServerWorld world, double x, double z) {
        int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING,
                (int) Math.floor(x), (int) Math.floor(z));
        return topY <= world.getBottomY() ? Double.NaN : (double) topY;
    }

    /**
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
    }

    /** 在 (x,z) ±6 邻域内找最高「真实 MOTION_BLOCKING 方块顶」；全空返回 NaN。 */
    private static double neighborTopY(ServerWorld world, double x, double z) {
        double best = Double.NaN;
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                double t = columnTopY(world, x + dx, z + dz);
                if (!Double.isNaN(t) && (Double.isNaN(best) || t > best)) {
                    best = t;
                }
            }
        }
        return best;
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

    // ---------- Geekhonize 账号绑定 ----------

    public void bindGeo(java.util.UUID id, String username, java.util.List<String> roles) {
        geoBinds.put(id, new String[]{username, String.join(",", roles)});
    }

    public void unbindGeo(java.util.UUID id) {
        geoBinds.remove(id);
    }

    /**
     * 账号唯一性：除 self 外是否已有在线玩家绑定了同一 Geekhonize 用户名。
     * 返回该玩家 UUID（无则 null），供服务端拦截第二处登录（顶号防护）。
     */
    public java.util.UUID geoBoundElsewhere(String username, java.util.UUID self) {
        for (var e : geoBinds.entrySet()) {
            if (!e.getKey().equals(self) && e.getValue()[0].equals(username)) {
                return e.getKey();
            }
        }
        return null;
    }

    /** 绑定的 Geekhonize 用户名（未绑定返回 null）。 */
    public String geoName(java.util.UUID id) {
        String[] b = geoBinds.get(id);
        return b == null ? null : b[0];
    }

    /** 绑定角色是否含 admin（管理权限凭证）。 */
    public boolean geoAdmin(java.util.UUID id) {
        String[] b = geoBinds.get(id);
        return b != null && b[1].contains("admin");
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
            bots.clearAll(BreakfrontServer.server());
        }
        saveServerProps();
    }

    public boolean setSpawnOverride(Side side, double x, double z) {
        double[] t = side == Side.ATTACKER ? attackerSpawn : defenderSpawn;
        t[0] = x;
        t[1] = z;
        saveServerProps(); // 持久化（props spawn.attacker/defender），重启不丢
        return true;
    }

    /** 设置 BF 出生点（大厅/进服落点）；传 NaN 恢复为世界出生点。持久化到 spawn.lobby。 */
    public void setLobbySpawn(double x, double z) {
        lobbySpawn[0] = x;
        lobbySpawn[1] = z;
        saveServerProps();
    }

    /** props 键值：override 生效时 "x,z"，否则空（不写）。 */
    private static String spawnKey(double[] ov) {
        return Double.isNaN(ov[0]) ? "" : String.format("%.1f,%.1f", ov[0], ov[1]);
    }

    /** Web 管理台「地图布局」快照：扇区顺序+据点坐标+出生点（JSON）。 */
    public String layoutJson(MinecraftServer server) {
        ServerWorld w = server.getOverworld();
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"sectors\":[");
        var defs = layout.sectors();
        for (int si = 0; si < defs.size(); si++) {
            if (si > 0) {
                sb.append(',');
            }
            var def = defs.get(si);
            sb.append("{\"idx\":").append(si)
                    .append(",\"name\":\"").append(esc(def.name())).append("\",\"zones\":[");
            var zs = def.zones();
            for (int zi = 0; zi < zs.size(); zi++) {
                if (zi > 0) {
                    sb.append(',');
                }
                var z = zs.get(zi);
                sb.append("{\"id\":\"").append(esc(z.id()))
                        .append("\",\"x\":").append(String.format("%.1f", z.x()))
                        .append(",\"z\":").append(String.format("%.1f", z.z()))
                        .append(",\"r\":").append(String.format("%.1f", z.radius())).append('}');
            }
            sb.append("]}");
        }
        sb.append("],\"editorSectorIdx\":").append(editorSectorIdx)
                .append(",\"totalZones\":").append(layout.zoneCount()).append(',');
        if (w != null) {
            double[] aa = spawnFor(Side.ATTACKER, w);
            double[] dd = spawnFor(Side.DEFENDER, w);
            sb.append("\"spawns\":{\"attacker\":{\"x\":").append(String.format("%.1f", aa[0]))
                    .append(",\"y\":").append(String.format("%.1f", aa[1]))
                    .append(",\"z\":").append(String.format("%.1f", aa[2])).append('}')
                    .append(",\"defender\":{\"x\":").append(String.format("%.1f", dd[0]))
                    .append(",\"y\":").append(String.format("%.1f", dd[1]))
                    .append(",\"z\":").append(String.format("%.1f", dd[2])).append("}}");
        } else {
            sb.append("\"spawns\":{}");
        }
        sb.append('}');
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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
                    } else if (k.equals("admin.password")) {
                        if (!v.isEmpty()) {
                            AdminService.password = v;
                        }
                    } else if (k.equals("admin.timeout")) {
                        try {
                            long t = Long.parseLong(v);
                            if (t > 0) {
                                AdminService.timeoutSecs = t;
                            }
                        } catch (NumberFormatException ignored) {
                            // 保留默认
                        }
                    } else if (k.equals("auth.endpoint")) {
                        if (!v.isBlank()) {
                            AuthBridge.endpoint = v.trim();
                        }
                    } else if (k.equals("spawn.attacker") || k.equals("spawn.defender")) {
                        int ci = v.indexOf(',');
                        if (ci > 0) {
                            try {
                                double sx = Double.parseDouble(v.substring(0, ci).trim());
                                double sz = Double.parseDouble(v.substring(ci + 1).trim());
                                double[] t = k.equals("spawn.attacker") ? attackerSpawn : defenderSpawn;
                                t[0] = sx;
                                t[1] = sz;
                            } catch (NumberFormatException ignored) {
                                // 忽略坏值
                            }
                        }
                    } else if (k.equals("spawn.lobby")) {
                        int ci = v.indexOf(',');
                        if (ci > 0) {
                            try {
                                lobbySpawn[0] = Double.parseDouble(v.substring(0, ci).trim());
                                lobbySpawn[1] = Double.parseDouble(v.substring(ci + 1).trim());
                            } catch (NumberFormatException ignored) {
                                // 忽略坏值
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            // 保持默认
        }
    }

    /**
     * 安全落盘文本文件（自动创建父目录）。
     *
     * <p>⚠️ {@code runDir} 可能是**空路径** —— 服务端以工作目录为运行目录启动时
     * {@code MinecraftServer.getRunDirectory()} 返回空 Path。此时
     * {@code runDir.resolve("x.properties")} 的 {@code getParent()} 为 <b>null</b>，
     * 直接 {@code Files.createDirectories(null)} 会抛
     * {@code NPE: Cannot invoke "java.nio.file.Path.getFileSystem()" because "path" is null}。
     * 这正是 Web 管理台「设置出生点」长期失败的原因（子目录型路径如
     * {@code breakfront/sectors.json} 的 parent 非 null，所以保存布局正常 —— 掩盖了问题）。
     */
    private static void writeText(Path file, String content) throws IOException {
        Path abs = file.toAbsolutePath();
        Path parent = abs.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(abs, content, StandardCharsets.UTF_8);
    }

    private void saveServerProps() {
        try {
            Path file = runDir.resolve("breakfront-server.properties");
            writeText(file, String.join("\n",
                    "# BREAKFRONT 服务端运行开关",
                    "fill=" + (autoFill ? "on" : "off"),
                    "autostart=" + (autostart ? "on" : "off"),
                    "# 管理员会话（M8）：密码与会话有效期（秒），/bfs 与 /bf admin goto 需此会话",
                    "admin.password=" + AdminService.password,
                    "admin.timeout=" + AdminService.timeoutSecs,
                    "# Geekhonize Auth 端点（Java core AuthBridge 校验玩家/管理台令牌）",
                    "auth.endpoint=" + AuthBridge.endpoint,
                    "# 出生点覆盖（/bf spawns set 或 Web 管理端写）：x,z",
                    "spawn.attacker=" + spawnKey(attackerSpawn),
                    "spawn.defender=" + spawnKey(defenderSpawn),
                    "# BF 出生点（大厅/进服落点）：留空=用世界出生点",
                    "spawn.lobby=" + spawnKey(lobbySpawn)) + "\n");
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
        } while (findZoneIndex(id) != null);
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
            writeText(file, layout.toJson());
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
                double gy = groundY(world, z.x(), z.z());
                zones.add(new SectorEditPayload.ZoneView(z.id(), si, z.x(), gy, z.z(), z.radius()));
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
