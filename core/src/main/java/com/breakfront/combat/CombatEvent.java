package com.breakfront.combat;

/**
 * 统一战斗事件（#47）。
 *
 * <p>设计对齐 656 的「玩法规则与表现解耦」：击杀归属（KillListener/TaCzKillAdapter）、
 * 计分（ScoreKeeper）、HUD/击杀流等全部作为本总线的<b>消费端</b>订阅同一事件流。
 *
 * <p>当前阶段为<b>加法接入</b>：总线与类型先行落地并单测，既有伤害/击杀链路暂不改动
 * （待稳定窗口再逐步把 KillListener/ScoreKeeper 迁移为消费端）。
 */
public record CombatEvent(
        CombatEventType type,
        String killerName,       // 制造者（玩家名或 AI id；无则 null）
        int killerSide,          // 0=攻方 1=守方 -1=未知
        boolean killerNpc,
        String victimName,       // 受害者（null=纯事件如占点）
        int victimSide,          // 同上语义；-1=未知
        String zoneId,           // 关联据点（占点/失守时非空）
        boolean headshot,        // 击杀事件是否爆头
        long atMillis
) {

    public static CombatEvent kill(String killerName, int killerSide, boolean killerNpc,
                                   String victimName, int victimSide, boolean headshot) {
        return new CombatEvent(CombatEventType.KILL, killerName, killerSide, killerNpc,
                victimName, victimSide, null, headshot, System.currentTimeMillis());
    }

    public static CombatEvent zone(CombatEventType type, String zoneId, int side) {
        return new CombatEvent(type, null, side, false, null, -1, zoneId, false,
                System.currentTimeMillis());
    }
}
