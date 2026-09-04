package com.breakfront.net;

import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 编译期桩 —— core 的 MatchStatePayload（S2C 状态帧）最小签名。
 * 绝不打包（见 client/build.gradle Jar exclude）；运行时由 core 模组提供真实类型。
 */
public class MatchStatePayload implements CustomPayload {

    public static final CustomPayload.Id<MatchStatePayload> ID =
            new CustomPayload.Id<>(Identifier.of("breakfront", "match_state"));

    private final int phaseOrdinal;
    private final int attackerTickets;
    private final float matchRemainingSeconds;
    private final float countdownRemainingSeconds;
    private final int sectorIndex;
    private final int sectorCount;
    private final List<ZoneStateView> currentSectorZones;

    public MatchStatePayload(int phaseOrdinal, int attackerTickets,
                             float matchRemainingSeconds, float countdownRemainingSeconds,
                             int sectorIndex, int sectorCount, List<ZoneStateView> zones) {
        this.phaseOrdinal = phaseOrdinal;
        this.attackerTickets = attackerTickets;
        this.matchRemainingSeconds = matchRemainingSeconds;
        this.countdownRemainingSeconds = countdownRemainingSeconds;
        this.sectorIndex = sectorIndex;
        this.sectorCount = sectorCount;
        this.currentSectorZones = new ArrayList<>(zones);
    }

    public int phaseOrdinal() {
        return phaseOrdinal;
    }

    public int attackerTickets() {
        return attackerTickets;
    }

    public float matchRemainingSeconds() {
        return matchRemainingSeconds;
    }

    public float countdownRemainingSeconds() {
        return countdownRemainingSeconds;
    }

    public int sectorIndex() {
        return sectorIndex;
    }

    public int sectorCount() {
        return sectorCount;
    }

    public List<ZoneStateView> currentSectorZones() {
        return currentSectorZones;
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getType() {
        return ID;
    }

    public record ZoneStateView(String zoneId, int ownerOrdinal, float meter) {
    }
}
