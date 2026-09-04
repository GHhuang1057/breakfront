package com.breakfront.server;

import com.tacz.guns.api.event.common.EntityKillByGunEvent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 击杀归属桥 · 第 2 层：TaCZ 枪械击杀适配。
 *
 * 订阅 TaCZ 的 EntityKillByGunEvent（服务端在弹头击杀结算时触发）：
 * - 提供枪械归属与爆头标记（比 vanilla 事件信息更全）
 * - 攻方扣票仍由第 1 层 vanilla AFTER_DEATH 负责（覆盖载具/坠落等全部死因），本层不重复扣
 * - 子弹/载具之外的死因不在本事件内，由第 1 层兜底
 *
 * 该类仅在运行时检测到 tacz 模组时才被装载（见 BreakfrontServer.register）。
 */
public final class TaCzKillAdapter {

    private TaCzKillAdapter() {
    }

    public static void register() {
        EntityKillByGunEvent.CALLBACK.register(TaCzKillAdapter::onKillByGun);
    }

    private static void onKillByGun(EntityKillByGunEvent event) {
        LivingEntity victim = event.getKilledEntity();
        LivingEntity attacker = event.getAttacker();
        if (!(victim instanceof ServerPlayerEntity victimPlayer)
                || !(attacker instanceof ServerPlayerEntity attackerPlayer)) {
            return; // 只把「玩家击杀玩家」写入击杀流
        }
        String attackerName = attackerPlayer.getGameProfile().getName();
        String victimName = victimPlayer.getGameProfile().getName();

        var match = BreakfrontServer.match();
        boolean attackerDied = match != null
                && match.teams().sideOf(victimPlayer.getUuid()) == com.breakfront.game.Side.ATTACKER;

        String weaponTag = event.getGunDisplayId() == null ? "" : " [" + event.getGunDisplayId() + "]";
        String headshotTag = event.isHeadShot() ? " [爆头]" : "";
        KillListener.pushRich(new KillListener.KillEntry(
                attackerName + weaponTag + headshotTag, victimName, attackerDied, event.isHeadShot()));
    }
}
