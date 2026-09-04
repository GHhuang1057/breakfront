package com.breakfront.server;

import com.breakfront.game.Side;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 击杀归属桥 —— 第 1 层：vanilla 事件层。
 *
 * 职责：把「玩家死亡」接到权威对局：
 * 1. 归属解析：DamageSource.getAttacker() → 兜底 victim.getAttacker()（近战/毒等场景）
 * 2. 只统计真实玩家死亡
 * 3. 攻方死亡 → 扣部署资源（规则层判定）
 * 4. 记录击杀流（P1 环形缓冲 + 日志；后续经网络同步给 breakfront-client HUD）
 *
 * 说明：TaCZ/SBW 等第三方武器若走 vanilla damage source 即可被本层捕获；
 * 若其子弹使用私有伤害类型，再由「TaCZ/SBW 适配器」（第二层）替换归属解析。
 */
public final class KillListener {

    public static final Logger LOGGER = LoggerFactory.getLogger("breakfront.kill");

    private static final Deque<KillEntry> recentKills = new ArrayDeque<>();
    private static ServerMatch match;

    private KillListener() {
    }

    public static void bind(ServerMatch currentMatch) {
        match = currentMatch;
    }

    /** 由 Fabric 事件 AFTER_DEATH 调用（仅在服务端）。 */
    public static void onEntityDeath(LivingEntity victim, DamageSource source) {
        if (!(victim instanceof ServerPlayerEntity player)) {
            // NPC 增援被击杀：击杀者记分 + 击杀流（不扣票、不生成 NPC 战绩条目）
            if (match != null && victim.getCommandTags().contains("breakfront.npc")
                    && resolveAttacker(source, victim) instanceof ServerPlayerEntity kp) {
                int botSide = victim.getCommandTags().contains("bf.side.att") ? 0 : 1;
                match.recordBotKill(kp, botSide);
                String vName = victim.getCustomName() != null
                        ? victim.getCustomName().getString() : victim.getName().getString();
                BreakfrontServer.notifyKill(new KillEntry(
                        kp.getGameProfile().getName(), vName, false, false));
            }
            return; // 只统计真实玩家/受控 NPC
        }
        var killer = resolveAttacker(source, victim);
        String killerName = killer instanceof ServerPlayerEntity p ? p.getGameProfile().getName() : (killer == null ? "环境" : killer.getName().getString());
        String victimName = player.getGameProfile().getName();

        boolean attackerDied = match != null
                && match.teams().sideOf(victim.getUuid()) == Side.ATTACKER;
        if (attackerDied) {
            match.game().onAttackerDeath(); // 攻方每死一人扣 1 部署资源
        }
        // S1：战中死亡把重生点钉在己方部署区
        if (match != null && player.getServer() != null) {
            match.onPlayerDied(player.getServer(), player);
        }
        if (match != null) {
            match.recordKill(player, killer instanceof LivingEntity le ? le : null);
        }
        push(new KillEntry(killerName, victimName, attackerDied, false));
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("kill: {} -> {} (attackerDied={}, tickets={})",
                    killerName, victimName, attackerDied,
                    match == null ? "?" : match.game().attackerTickets());
        }
    }

    /** 归属解析（第一版规则，见类注释）。 */
    private static LivingEntity resolveAttacker(DamageSource source, LivingEntity victim) {
        var fromSource = source.getAttacker();
        if (fromSource instanceof LivingEntity living) {
            return living;
        }
        return victim.getAttacker(); // 兜底：最近伤害者
    }

    private static void push(KillEntry entry) {
        recentKills.addFirst(entry);
        while (recentKills.size() > 20) {
            recentKills.removeLast();
        }
        BreakfrontServer.notifyKill(entry); // 广播给所有客户端击杀流
    }

    /** 供第 2 层适配器（TaCZ 等）写入更丰富的击杀流。 */
    public static void pushRich(KillEntry entry) {
        push(entry);
    }

    public static Deque<KillEntry> recentKills() {
        return recentKills;
    }

    public record KillEntry(String killer, String victim, boolean attackerDied, boolean headshot) {
    }
}
