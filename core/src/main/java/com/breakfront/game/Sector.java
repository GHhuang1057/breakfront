package com.breakfront.game;

import java.util.List;

/**
 * 扇区：攻防推进中的一个防线段落，含 1..N 个据点。
 * 攻方须占满当前扇区全部据点后方可推进至下一扇区。
 */
public record Sector(String id, String nameCn, List<ZoneState> zones) {

    public Sector {
        zones = List.copyOf(zones);
    }

    public boolean isFullyCapturedBy(Side side) {
        return !zones.isEmpty() && zones.stream().allMatch(z -> z.owner() == side);
    }
}
