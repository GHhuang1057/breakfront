package com.breakfront.server;

import com.breakfront.game.MatchPhase;
import com.breakfront.game.Sector;
import com.breakfront.game.Side;
import com.breakfront.game.ZoneState;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * /bf 管理指令（打靶房与调试用）。
 * 子命令：start / status / end / team <attacker|defender> / kills
 */
public final class BreakfrontCommands {

    private BreakfrontCommands() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                CommandRegistryAccess registryAccess,
                                CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(literal("bf")
                .then(literal("start").requires(s -> s.hasPermissionLevel(2))
                        .executes(ctx -> {
                            var match = BreakfrontServer.match();
                            if (match == null) {
                                ctx.getSource().sendError(Text.literal("对局尚未初始化"));
                                return 0;
                            }
                            match.game().startRound();
                            send(ctx.getSource(), "对局开始：部署倒计时 "
                                    + String.format("%.0f 秒", match.game().countdownRemaining()));
                            return 1;
                        }))
                .then(literal("end").requires(s -> s.hasPermissionLevel(2))
                        .executes(ctx -> {
                            var match = BreakfrontServer.match();
                            if (match == null) {
                                ctx.getSource().sendError(Text.literal("对局尚未初始化"));
                                return 0;
                            }
                            match.game().endRound(com.breakfront.game.MatchResult.DEFENDER_WIN);
                            send(ctx.getSource(), "对局强制结算（守方胜）");
                            return 1;
                        }))
                .then(literal("team")
                        .executes(ctx -> {
                            var source = ctx.getSource();
                            if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
                                source.sendError(Text.literal("仅玩家可加入阵营"));
                                return 0;
                            }
                            send(source, "用法：/bf team <attacker|defender>");
                            return 0;
                        })
                        .then(literal("attacker").executes(ctx -> joinTeam(ctx.getSource(), Side.ATTACKER)))
                        .then(literal("defender").executes(ctx -> joinTeam(ctx.getSource(), Side.DEFENDER))))
                .then(literal("status").executes(ctx -> {
                    var match = BreakfrontServer.match();
                    if (match == null) {
                        ctx.getSource().sendError(Text.literal("对局尚未初始化"));
                        return 0;
                    }
                    send(ctx.getSource(), status(match));
                    return 1;
                }))
                .then(literal("kills").executes(ctx -> {
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
                })));
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
        int idx = 0;
        for (Sector sector : g.sectors()) {
            sb.append("\n[扇区] ").append(sector.nameCn());
            for (ZoneState zone : sector.zones()) {
                sb.append(' ').append(zone.id()).append('(').append(zone.owner().labelCn)
                        .append(String.format(" %.0f%%", zone.meter() * 100)).append(')');
                idx++;
            }
        }
        sb.append("\n攻方人数=").append(match.teams().count(Side.ATTACKER))
                .append(" 守方人数=").append(match.teams().count(Side.DEFENDER));
        return sb.toString();
    }

    private static String formatClock(double seconds) {
        int total = (int) Math.max(0, Math.ceil(seconds));
        return String.format("%02d:%02d", total / 60, total % 60);
    }

    private static void send(ServerCommandSource source, String message) {
        source.sendFeedback(() -> Text.literal(message), false);
    }
}
