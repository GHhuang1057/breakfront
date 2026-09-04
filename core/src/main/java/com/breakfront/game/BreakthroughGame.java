package com.breakfront.game;

import java.util.ArrayList;
import java.util.List;

/**
 * 攻防对局状态机（纯逻辑，服务端权威将在其上做网络/实体适配）。
 *
 * 流程：LOBBY → COUNTDOWN → BATTLE → ROUND_END →（RESET →）LOBBY
 * 胜负：攻方耗尽部署资源 / 总时限到 = 守方胜；推完末扇区并占满 = 攻方胜。
 * 所有时间以「秒」为单位；Minecraft tick 适配层（0.05s/tick）在接入服务端时实现。
 */
public class BreakthroughGame {

    private final List<Sector> sectors;
    private final int attackerTicketsMax;
    private final double matchSeconds;
    private final double countdownSeconds;

    private MatchPhase phase = MatchPhase.LOBBY;
    private MatchResult result = MatchResult.NONE;

    private double phaseTimer;   // COUNTDOWN 剩余秒数
    private double matchTimer;   // BATTLE 剩余秒数
    private int attackerTickets; // 攻方剩余部署资源
    private int sectorIndex;     // 当前推进到第几个扇区（从 0 起）

    public BreakthroughGame(List<Sector> sectors) {
        this(sectors, BreakthroughTuning.DEFAULT_ATTACKER_TICKETS,
                BreakthroughTuning.DEFAULT_MATCH_SECONDS, BreakthroughTuning.DEFAULT_COUNTDOWN_SECONDS);
    }

    public BreakthroughGame(List<Sector> sectors, int attackerTicketsMax,
                            double matchSeconds, double countdownSeconds) {
        if (sectors == null || sectors.isEmpty()) {
            throw new IllegalArgumentException("sectors must not be empty");
        }
        this.sectors = List.copyOf(sectors);
        this.attackerTicketsMax = attackerTicketsMax;
        this.matchSeconds = matchSeconds;
        this.countdownSeconds = countdownSeconds;
        resetState();
    }

    /** 新开一局：允许从 LOBBY 或 ROUND_END 进入，重置全量状态并开始倒计时。 */
    public void startRound() {
        if (phase == MatchPhase.COUNTDOWN || phase == MatchPhase.BATTLE) {
            throw new IllegalStateException("cannot start a round while phase is " + phase);
        }
        resetState();
        phase = MatchPhase.COUNTDOWN;
        phaseTimer = countdownSeconds;
    }

    /** 强制回到大厅（管理端 /bf stop 用）：清空本局状态并停表。 */
    public void returnToLobby() {
        resetState();
        phase = MatchPhase.LOBBY;
    }

    private void resetState() {
        result = MatchResult.NONE;
        attackerTickets = attackerTicketsMax;
        sectorIndex = 0;
        matchTimer = matchSeconds;
        phaseTimer = 0;
        for (Sector sector : sectors) {
            for (ZoneState zone : sector.zones()) {
                zone.reset();
            }
        }
    }

    /**
     * 主循环步进（每秒 dt 秒）。服务端适配层将按 0.05 秒/tick 调用。
     */
    public void tick(double dtSeconds) {
        if (dtSeconds <= 0) {
            return;
        }
        switch (phase) {
            case COUNTDOWN -> {
                phaseTimer -= dtSeconds;
                if (phaseTimer <= 0) {
                    phase = MatchPhase.BATTLE;
                    matchTimer = matchSeconds;
                }
            }
            case BATTLE -> {
                matchTimer -= dtSeconds;
                if (matchTimer <= 0) {
                    endRound(MatchResult.DEFENDER_WIN);
                }
            }
            default -> {
                // LOBBY / ROUND_END / RESET 不做自动推进
            }
        }
    }

    /**
     * 每 tick 对某个据点做人数结算。
     *
     * @param zoneIndex 全局据点序号（跨扇区线性编号）
     * @return 本 tick 产生的据点事件
     */
    public List<ZoneEvent> applyZonePresence(int zoneIndex, int attackerInside,
                                             int defenderInside, double dtSeconds) {
        List<ZoneEvent> events = new ArrayList<>();
        if (phase != MatchPhase.BATTLE) {
            return events;
        }
        ZoneState zone = zoneAt(zoneIndex);
        if (zone == null) {
            return events;
        }
        ZoneEvent event = zone.update(attackerInside, defenderInside, dtSeconds);
        if (event != null) {
            events.add(event);
            if (event.capturedBy() == Side.ATTACKER) {
                onSectorClearedIfReady();
            }
        }
        return events;
    }

    /** 攻方每死亡一人调用一次：扣 1 部署资源。 */
    public void onAttackerDeath() {
        if (phase != MatchPhase.BATTLE) {
            return;
        }
        attackerTickets--;
        if (attackerTickets <= 0) {
            endRound(MatchResult.DEFENDER_WIN);
        }
    }

    /** 推进判定：当前扇区已被攻方占满则后移防线。 */
    private void onSectorClearedIfReady() {
        Sector current = sectors.get(sectorIndex);
        if (!current.isFullyCapturedBy(Side.ATTACKER)) {
            return;
        }
        if (sectorIndex >= sectors.size() - 1) {
            endRound(MatchResult.ATTACKER_WIN);
        } else {
            sectorIndex++;
        }
    }

    private ZoneState zoneAt(int globalIndex) {
        int idx = 0;
        for (Sector sector : sectors) {
            for (ZoneState zone : sector.zones()) {
                if (idx == globalIndex) {
                    return zone;
                }
                idx++;
            }
        }
        return null;
    }

    /** 总据点数量（客户端 HUD / 计分板需要）。 */
    public int zoneCount() {
        int n = 0;
        for (Sector sector : sectors) {
            n += sector.zones().size();
        }
        return n;
    }

    public void endRound(MatchResult roundResult) {
        result = roundResult;
        phase = MatchPhase.ROUND_END;
    }

    public MatchPhase phase() {
        return phase;
    }

    public MatchResult result() {
        return result;
    }

    public int attackerTickets() {
        return attackerTickets;
    }

    public int sectorIndex() {
        return sectorIndex;
    }

    public Sector currentSector() {
        return sectors.get(sectorIndex);
    }

    /** COUNTDOWN 剩余秒数。 */
    public double countdownRemaining() {
        return Math.max(0, phaseTimer);
    }

    /** BATTLE 剩余秒数。 */
    public double matchRemaining() {
        return Math.max(0, matchTimer);
    }

    public List<Sector> sectors() {
        return sectors;
    }
}
