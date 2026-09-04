package com.breakfront.client.state;

import com.breakfront.net.KillFeedPayload;
import com.breakfront.net.MatchStatePayload;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端对局状态单例：接收 S2C 状态帧/击杀流，供 HUD 与后续全屏界面读取。
 * 仅在渲染线程内更新与读取（接收回调中已切主线程执行）。
 */
public final class ClientMatchState {

    // 对局状态
    private static volatile int phaseOrdinal;
    private static volatile int attackerTickets;
    private static volatile float matchRemainingSeconds;
    private static volatile float countdownRemainingSeconds;
    private static volatile int sectorIndex;
    private static volatile int sectorCount;
    private static final List<ZoneView> zones = new ArrayList<>();

    // 击杀流（时间戳由接收方写入，HUD 负责淡出）
    private static final List<KillEvent> killFeed = new ArrayList<>();

    /** 阶段切换计数（每次状态帧阶段变化 +1，供「每局弹一次部署页」判定）。 */
    private static int phaseChangeCount;

    private ClientMatchState() {
    }

    public static void applyMatch(MatchStatePayload payload) {
        if (payload.phaseOrdinal() != phaseOrdinal) {
            phaseChangeCount++;
        }
        phaseOrdinal = payload.phaseOrdinal();
        attackerTickets = payload.attackerTickets();
        matchRemainingSeconds = payload.matchRemainingSeconds();
        countdownRemainingSeconds = payload.countdownRemainingSeconds();
        sectorIndex = payload.sectorIndex();
        sectorCount = payload.sectorCount();
        zones.clear();
        for (MatchStatePayload.ZoneStateView z : payload.currentSectorZones()) {
            zones.add(new ZoneView(z.zoneId(), z.letter(), z.ownerOrdinal(), z.meter(),
                    z.worldX(), z.worldZ(), z.groundY(), z.radius()));
        }
    }

    public static void applyKill(KillFeedPayload payload) {
        killFeed.add(0, new KillEvent(payload.killer(), payload.victim(),
                payload.attackerDied(), payload.headshot(), System.currentTimeMillis()));
        while (killFeed.size() > 6) {
            killFeed.remove(killFeed.size() - 1);
        }
    }

    public static int phaseOrdinal() {
        return phaseOrdinal;
    }

    public static int phaseChangeCount() {
        return phaseChangeCount;
    }

    public static int attackerTickets() {
        return attackerTickets;
    }

    public static float matchRemainingSeconds() {
        return matchRemainingSeconds;
    }

    public static float countdownRemainingSeconds() {
        return countdownRemainingSeconds;
    }

    public static int sectorIndex() {
        return sectorIndex;
    }

    public static int sectorCount() {
        return sectorCount;
    }

    public static List<ZoneView> zones() {
        return zones;
    }

    public static List<KillEvent> killFeed() {
        return killFeed;
    }

    public record ZoneView(String zoneId, String letter, int ownerOrdinal, float meter,
                           double worldX, double worldZ, double groundY, float radius) {
    }

    public record KillEvent(String killer, String victim,
                            boolean attackerDied, boolean headshot, long addedAt) {
    }
}
