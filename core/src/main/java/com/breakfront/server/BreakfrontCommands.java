package com.breakfront.server;

import com.breakfront.game.MatchPhase;
import com.breakfront.game.MatchResult;
import com.breakfront.game.Sector;
import com.breakfront.game.Side;
import com.breakfront.game.ZoneState;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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

        dispatcher.register(root);
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
                .then(ovNode("defender", Side.DEFENDER)));
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

    private static LiteralArgumentBuilder<ServerCommandSource> npcNode() {
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
    }

    private static void send(ServerCommandSource source, String message) {
        source.sendFeedback(() -> Text.literal(message), false);
    }
}
