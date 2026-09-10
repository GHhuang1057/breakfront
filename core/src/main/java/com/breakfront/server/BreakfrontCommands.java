package com.breakfront.server;

import com.breakfront.game.MatchPhase;
import com.breakfront.game.MatchResult;
import com.breakfront.game.Sector;
import com.breakfront.game.Side;
import com.breakfront.game.ZoneState;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * /bf 管理指令（打靶房与调试用）。
 * 子命令：start / status / end / team / kills / map on|off / anchor &lt;index&gt; &lt;x&gt; &lt;z&gt;
 */
public final class BreakfrontCommands {

    private BreakfrontCommands() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                CommandRegistryAccess registryAccess,
                                CommandManager.RegistrationEnvironment environment) {
        var root = literal("bf");

        root = root.then(literal("start").requires(s -> s.hasPermissionLevel(2))
                .executes(ctx -> {
                    var match = BreakfrontServer.match();
                    if (match == null) {
                        ctx.getSource().sendError(Text.literal("对局尚未初始化"));
                        return 0;
                    }
                    match.beginRound();
                    send(ctx.getSource(), "对局开始：部署倒计时 "
                            + String.format("%.0f 秒", match.game().countdownRemaining()));
                    return 1;
                }));

        root = root.then(literal("end").requires(s -> s.hasPermissionLevel(2))
                .executes(ctx -> {
                    var match = BreakfrontServer.match();
                    if (match == null) {
                        ctx.getSource().sendError(Text.literal("对局尚未初始化"));
                        return 0;
                    }
                    match.game().endRound(MatchResult.DEFENDER_WIN);
                    send(ctx.getSource(), "对局强制结算（守方胜），8 秒后将自动重开");
                    return 1;
                }));

        root = root.then(literal("stop").requires(s -> s.hasPermissionLevel(2))
                .executes(ctx -> {
                    var match = BreakfrontServer.match();
                    if (match == null) {
                        ctx.getSource().sendError(Text.literal("对局尚未初始化"));
                        return 0;
                    }
                    match.game().returnToLobby();
                    send(ctx.getSource(), "对局已停止，回到大厅（/bf start 重新开局）");
                    return 1;
                }));

                root = root.then(autostartNode());
        root = root.then(fillNode());
        root = root.then(spawnsNode());
        root = root.then(npcNode());
        root = root.then(botNode());

root = root.then(literal("team")
                .executes(ctx -> {
                    send(ctx.getSource(), "用法：/bf team <attacker|defender>");
                    return 0;
                })
                .then(literal("attacker").executes(ctx -> joinTeam(ctx.getSource(), Side.ATTACKER)))
                .then(literal("defender").executes(ctx -> joinTeam(ctx.getSource(), Side.DEFENDER))));

        root = root.then(literal("status").executes(ctx -> {
            var match = BreakfrontServer.match();
            if (match == null) {
                ctx.getSource().sendError(Text.literal("对局尚未初始化"));
                return 0;
            }
            send(ctx.getSource(), status(match));
            return 1;
        }));

        root = root.then(literal("kills").executes(ctx -> {
            var kills = KillListener.recentKills();
            if (kills.isEmpty()) {
                send(ctx.getSource(), "暂无击杀记录");
            } else {
                StringBuilder sb = new StringBuilder();
                for (KillListener.KillEntry k : kills) {
                    sb.append(k.killer()).append(" → ").append(k.victim())
                            .append(k.attackerDied() ? "（攻方减员）" : "（守方减员）").append('\n');
                }
                send(ctx.getSource(), sb.toString());
            }
            return 1;
        }));

        root = root.then(literal("board").executes(ctx -> {
            var match = BreakfrontServer.match();
            if (match == null) {
                ctx.getSource().sendError(Text.literal("对局尚未初始化"));
                return 0;
            }
            send(ctx.getSource(), match.scoreText());
            return 1;
        }));

        root = root.then(literal("map")
                .then(literal("off").executes(ctx -> {
                    var match = BreakfrontServer.match();
                    if (match != null) {
                        match.setArenaSkipped(true);
                    }
                    send(ctx.getSource(), "已跳过自建城市（使用外部地图世界）");
                    return 1;
                }))
                .then(literal("on").executes(ctx -> {
                    var match = BreakfrontServer.match();
                    if (match != null) {
                        match.setArenaSkipped(false);
                    }
                    send(ctx.getSource(), "已启用自建城市（下次重启用 viaduct 重建）");
                    return 1;
                })));

        LiteralArgumentBuilder<ServerCommandSource> anchor = literal("anchor");
        for (int i = 0; i < 8; i++) {
            final int index = i;
            anchor = anchor.then(literal(String.valueOf(index)).then(literal("set")
                    .then(CommandManager.argument("x", DoubleArgumentType.doubleArg())
                            .then(CommandManager.argument("z", DoubleArgumentType.doubleArg())
                                    .executes(ctx -> {
                                        var match = BreakfrontServer.match();
                                        if (match == null) {
                                            ctx.getSource().sendError(Text.literal("对局尚未初始化"));
                                            return 0;
                                        }
                                        double x = DoubleArgumentType.getDouble(ctx, "x");
                                        double z = DoubleArgumentType.getDouble(ctx, "z");
                                        if (!match.moveAnchor(index, x, z)) {
                                            ctx.getSource().sendError(Text.literal("据点序号无效：0.."
                                                    + (match.game().zoneCount() - 1)));
                                            return 0;
                                        }
                                        send(ctx.getSource(), String.format(
                                                "据点[%d] 锚点移至 (%.1f, %.1f)，下次开战重铺标识", index, x, z));
                                        return 1;
                                    })))));
        }
        root = root.then(anchor);

        // M2：重发兵种装备（/bf kit 自己补装；/bf kit <class> [gunId] 切兵种/武器并立即补装）
        root = root.then(literal("kit")
                .executes(ctx -> kitSelf(ctx.getSource(), null, null))
                .then(CommandManager.argument("class", StringArgumentType.word())
                        .executes(ctx -> kitSelf(ctx.getSource(),
                                StringArgumentType.getString(ctx, "class"), null))
                        .then(CommandManager.argument("gun", StringArgumentType.word())
                                .executes(ctx -> kitSelf(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "class"),
                                        StringArgumentType.getString(ctx, "gun"))))));

        // 任务 A：部署点选择（死亡/部署页用）。无参不回大厅（仅提示用法）。
        root = root.then(literal("deploy")
                .executes(ctx -> deploy(ctx.getSource(), "base"))
                .then(CommandManager.argument("target", StringArgumentType.word())
                        .executes(ctx -> deploy(ctx.getSource(),
                                StringArgumentType.getString(ctx, "target")))));

        // M8：管理员会话（文本路径；客户端面板走 C2S 载荷）。goto 需要 op2 或管理员会话。
        root = root.then(literal("admin")
                .then(literal("login")
                        .then(CommandManager.argument("password", StringArgumentType.word())
                                .executes(ctx -> {
                                    String pwd = StringArgumentType.getString(ctx, "password");
                                    if (!(ctx.getSource().getEntity() instanceof ServerPlayerEntity p)) {
                                        send(ctx.getSource(), "仅玩家可用");
                                        return 1;
                                    }
                                    boolean ok = AdminService.login(p.getUuid(), pwd);
                                    send(ctx.getSource(), ok
                                            ? "管理员会话已建立（" + AdminService.timeoutSecs + " 秒有效，/bf admin logout 注销）"
                                            : "密码错误");
                                    return 1;
                                })))
                .then(literal("logout").executes(ctx -> {
                    if (ctx.getSource().getEntity() instanceof ServerPlayerEntity p) {
                        AdminService.logout(p.getUuid());
                    }
                    send(ctx.getSource(), "管理员会话已注销");
                    return 1;
                }))
                .then(literal("status").executes(ctx -> {
                    boolean admin = ctx.getSource().getEntity() instanceof ServerPlayerEntity p
                            && AdminService.has(p.getUuid());
                    send(ctx.getSource(), admin ? "管理员会话生效中"
                            : "未登录管理员（/bf admin login <密码>，或 Ctrl+Shift+F8 面板）");
                    return 1;
                }))
                .then(literal("goto")
                        .requires(s -> AdminService.allows(s))
                        .then(CommandManager.argument("zone", StringArgumentType.word())
                                .executes(ctx -> {
                                    var match = BreakfrontServer.match();
                                    if (match == null) {
                                        send(ctx.getSource(), "对局尚未初始化");
                                        return 1;
                                    }
                                    if (!(ctx.getSource().getEntity() instanceof ServerPlayerEntity p)) {
                                        send(ctx.getSource(), "仅玩家可用");
                                        return 1;
                                    }
                                    String zone = StringArgumentType.getString(ctx, "zone");
                                    send(ctx.getSource(), match.adminGoto(
                                            ctx.getSource().getServer(), p, zone));
                                    return 1;
                                }))));

        dispatcher.register(root);
    }

    private static int kitSelf(ServerCommandSource source, String classId, String gunId) {
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.literal("仅玩家可领装备"));
            return 0;
        }
        var match = BreakfrontServer.match();
        if (match == null) {
            source.sendError(Text.literal("对局尚未初始化"));
            return 0;
        }
        if (classId != null) {
            if (!java.util.Arrays.asList(TeamManager.KNOWN_CLASSES).contains(classId)) {
                source.sendError(Text.literal("未知兵种：" + classId));
                return 0;
            }
            match.teams().setClass(player.getUuid(), classId);
        }
        String actualClass = match.teams().classOf(player.getUuid());
        if (gunId != null) {
            if (com.breakfront.weapon.WeaponCatalog.isGunAllowed(actualClass, gunId)) {
                match.teams().setGun(player.getUuid(), gunId);
            } else {
                // 不在白名单：回退该兵种默认枪，并提示
                match.teams().setGun(player.getUuid(),
                        com.breakfront.weapon.WeaponCatalog.defaultGun(actualClass));
                source.sendFeedback(() -> Text.literal(
                        "枪 " + gunId + " 不属于 " + actualClass + " 白名单，已回退默认枪 "
                                + com.breakfront.weapon.WeaponCatalog.defaultGun(actualClass)), false);
            }
        }
        match.kitPlayer(player.getServer(), player);
        String actualGun = match.teams().gunIdOf(player.getUuid());
        String gunLabel = actualGun != null
                ? actualGun : com.breakfront.weapon.WeaponCatalog.defaultGun(actualClass);
        send(source, "装备已补发（兵种 " + actualClass + " / 主武器 " + gunLabel + "）");
        return 1;
    }

    private static int deploy(ServerCommandSource source, String target) {
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.literal("仅玩家可部署"));
            return 0;
        }
        var match = BreakfrontServer.match();
        if (match == null) {
            source.sendError(Text.literal("对局尚未初始化"));
            return 0;
        }
        match.setDeployChoice(player.getServer(), player, target);
        if ("observe".equalsIgnoreCase(target)) {
            send(source, "已进入观察模式（旁观）");
        } else {
            send(source, "已选择重生点：" + target + "（点击部署后生效）");
        }
        return 1;
    }

    private static int joinTeam(ServerCommandSource source, Side side) {
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.literal("仅玩家可加入阵营"));
            return 0;
        }
        var match = BreakfrontServer.match();
        if (match != null) {
            match.teams().join(player.getUuid(), side);
        }
        send(source, "你已加入：" + side.labelCn);
        return 1;
    }

    private static String status(ServerMatch match) {
        var g = match.game();
        StringBuilder sb = new StringBuilder();
        sb.append("阶段：").append(g.phase().labelCn);
        if (g.phase() == MatchPhase.BATTLE) {
            sb.append(" ｜ 攻方部署 ").append(g.attackerTickets())
                    .append(" ｜ 剩余 ").append(formatClock(g.matchRemaining()))
                    .append(" ｜ 扇区 ").append(g.sectorIndex() + 1).append('/').append(g.sectors().size());
        }
        for (Sector sector : g.sectors()) {
            sb.append("\n[扇区] ").append(sector.nameCn());
            for (ZoneState zone : sector.zones()) {
                sb.append(' ').append(zone.id()).append('(').append(zone.owner().labelCn)
                        .append(String.format(" %.0f%%", zone.meter() * 100)).append(')');
            }
        }
        sb.append("\n攻方人数=").append(match.teams().count(Side.ATTACKER))
                .append(" 守方人数=").append(match.teams().count(Side.DEFENDER))
                .append(" 自动开局=").append(match.autostartEnabled() ? "开" : "关")
                .append(" AI填充=").append(match.autoFillEnabled() ? "开" : "关");
        sb.append("\n据点锚点（/bf anchor <idx> set x z 可改）：").append(match.zoneAnchorsText());
        return sb.toString();
    }

    private static String formatClock(double seconds) {
        int total = (int) Math.max(0, Math.ceil(seconds));
        return String.format("%02d:%02d", total / 60, total % 60);
    }

    // ---------- 子命令节点构建（避免深嵌套括号） ----------

    private static LiteralArgumentBuilder<ServerCommandSource> autostartNode() {
        return literal("autostart").requires(s -> s.hasPermissionLevel(2))
                .then(literal("on").executes(ctx -> {
                    var match = BreakfrontServer.match();
                    if (match == null) {
                        ctx.getSource().sendError(Text.literal("对局尚未初始化"));
                        return 0;
                    }
                    match.setAutostart(true);
                    send(ctx.getSource(), "自动开局已开启：大厅双阵营就绪后 5 秒开局");
                    return 1;
                }))
                .then(literal("off").executes(ctx -> {
                    var match = BreakfrontServer.match();
                    if (match != null) {
                        match.setAutostart(false);
                    }
                    send(ctx.getSource(), "自动开局已关闭");
                    return 1;
                }));
    }

    /** AI 自动填充（人机/单机=一真人其余 AI）：开=有真人即 16v16 填充 5s 自动开局。 */
    private static LiteralArgumentBuilder<ServerCommandSource> fillNode() {
        return literal("fill").requires(s -> s.hasPermissionLevel(2))
                .then(literal("on").executes(ctx -> {
                    var match = BreakfrontServer.match();
                    if (match == null) {
                        ctx.getSource().sendError(Text.literal("对局尚未初始化"));
                        return 0;
                    }
                    match.setAutoFill(true);
                    send(ctx.getSource(), "AI 填充已开启：大厅有真人时补齐 16v16 并在 5 秒后自动开局");
                    return 1;
                }))
                .then(literal("off").executes(ctx -> {
                    var match = BreakfrontServer.match();
                    if (match != null) {
                        match.setAutoFill(false);
                    }
                    send(ctx.getSource(), "AI 填充已关闭：仅真实玩家对局（可用 /bf autostart 或 /bf start）");
                    return 1;
                }));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> spawnsNode() {
        LiteralArgumentBuilder<ServerCommandSource> node = literal("spawns").requires(s -> s.hasPermissionLevel(2))
                .executes(ctx -> {
                    var match = BreakfrontServer.match();
                    if (match == null) {
                        ctx.getSource().sendError(Text.literal("对局尚未初始化"));
                        return 0;
                    }
                    send(ctx.getSource(), match.spawnsText(BreakfrontServer.server()));
                    return 1;
                });
        return node.then(literal("set")
                .then(ovNode("attacker", Side.ATTACKER))
                .then(ovNode("defender", Side.DEFENDER)))
                // /bf spawns lobby [x z]：不带坐标=把出生点设为你当前站的位置；clear=恢复世界出生点
                .then(literal("lobby")
                        .executes(ctx -> {
                            var match = BreakfrontServer.match();
                            if (match == null) {
                                ctx.getSource().sendError(Text.literal("对局尚未初始化"));
                                return 0;
                            }
                            var src = ctx.getSource();
                            var pos = src.getPosition();
                            match.setLobbySpawn(pos.x, pos.z);
                            match.forceLoadSpawnChunks(BreakfrontServer.server(), 48);
                            send(src, String.format("BF 出生点已设为当前位置 (%.1f, %.1f) 并常驻加载",
                                    pos.x, pos.z));
                            return 1;
                        })
                        .then(CommandManager.argument("x", DoubleArgumentType.doubleArg())
                                .then(CommandManager.argument("z", DoubleArgumentType.doubleArg())
                                        .executes(ctx -> {
                                            var match = BreakfrontServer.match();
                                            if (match == null) {
                                                return 0;
                                            }
                                            double x = DoubleArgumentType.getDouble(ctx, "x");
                                            double z = DoubleArgumentType.getDouble(ctx, "z");
                                            match.setLobbySpawn(x, z);
                                            match.forceLoadSpawnChunks(BreakfrontServer.server(), 48);
                                            send(ctx.getSource(), String.format(
                                                    "BF 出生点已设为 (%.1f, %.1f) 并常驻加载", x, z));
                                            return 1;
                                        }))))
                .then(literal("lobbyclear").executes(ctx -> {
                    var match = BreakfrontServer.match();
                    if (match == null) {
                        return 0;
                    }
                    match.setLobbySpawn(Double.NaN, Double.NaN);
                    send(ctx.getSource(), "BF 出生点已恢复为世界出生点");
                    return 1;
                }));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> ovNode(String label, Side side) {
        return literal(label)
                .then(CommandManager.argument("x", DoubleArgumentType.doubleArg())
                        .then(CommandManager.argument("z", DoubleArgumentType.doubleArg())
                                .executes(ctx -> {
                                    var match = BreakfrontServer.match();
                                    if (match == null) {
                                        return 0;
                                    }
                                    match.setSpawnOverride(side,
                                            DoubleArgumentType.getDouble(ctx, "x"),
                                            DoubleArgumentType.getDouble(ctx, "z"));
                                    send(ctx.getSource(), label + " 出生点已覆盖");
                                    return 1;
                                })));
    }

    /**
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
    }

    /**
     * {@code /bf bot …} —— AI 假玩家小队运维入口。
     *
     * <p>常态编制由大厅赛程自动维持（{@code /bf fill on}），真人进服会自动热顶替一个 BOT；
     * 这里的 {@code trial} / {@code clear} 是**手动接管**编制用的调试开关。
     */
    private static LiteralArgumentBuilder<ServerCommandSource> botNode() {
        return literal("bot").requires(s -> s.hasPermissionLevel(2))
                .then(literal("status").executes(ctx -> {
                    var match = BreakfrontServer.match();
                    if (match == null) {
                        return 0;
                    }
                    send(ctx.getSource(), match.bots().info());
                    String roster = match.bots().roster();
                    if (!roster.isEmpty()) {
                        send(ctx.getSource(), "名册：" + roster);
                    }
                    return 1;
                }))
                .then(literal("trial")
                        .then(CommandManager.argument("n", IntegerArgumentType.integer(1, 32))
                                .executes(ctx -> {
                                    var match = BreakfrontServer.match();
                                    var server = BreakfrontServer.server();
                                    if (match == null || server == null) {
                                        ctx.getSource().sendError(Text.literal("服务端未就绪"));
                                        return 0;
                                    }
                                    int n = IntegerArgumentType.getInteger(ctx, "n");
                                    match.bots().setTarget(Side.ATTACKER, n);
                                    match.bots().setTarget(Side.DEFENDER, n);
                                    match.bots().reconcile(match, server);
                                    send(ctx.getSource(), "已生成 " + n + "v" + n
                                            + " 假玩家小队；清除用 /bf bot clear，查看用 /bf bot status");
                                    return 1;
                                })))
                .then(literal("clear").executes(ctx -> {
                    var match = BreakfrontServer.match();
                    if (match == null) {
                        return 0;
                    }
                    match.bots().clearAll(BreakfrontServer.server());
                    send(ctx.getSource(), "已清除全部假玩家");
                    return 1;
                }));
    }

    private static void send(ServerCommandSource source, String message) {
        source.sendFeedback(() -> Text.literal(message), false);
    }}
