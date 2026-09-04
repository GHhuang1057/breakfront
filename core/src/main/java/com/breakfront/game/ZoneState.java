package com.breakfront.game;

/**
 * 据点（目标点）状态。
 *
 * 模型（贴合 BF 突破）：meter ∈ [0,1] 表示**攻方推进度**。
 * - 攻方人数占优 → meter 上升；到 1.0 时易手给攻方（meter 保持 1 表示已固守）。
 * - 守方人数占优 → meter 被清空（拖慢/抵消攻方进度）；若攻方已得手，清到 0.0 即夺回给守方。
 * - 任何易手都必须完整推满（或清空）一杆，杜绝瞬间易手；守方在自家点位上只会抵消进度，不会"负向占坑"。
 *
 * 纯 Java、无 Minecraft 依赖，便于单元测试与服务端权威接入。
 */
public class ZoneState {

    private final String id;
    private final double captureSeconds; // 人数优势 1v0 推满一杆所需秒数
    private Side owner;
    private double meter; // [0,1]，攻方推进度

    public ZoneState(String id, double captureSeconds) {
        this(id, Side.DEFENDER, captureSeconds);
    }

    public ZoneState(String id, Side initialOwner, double captureSeconds) {
        if (captureSeconds <= 0) {
            throw new IllegalArgumentException("captureSeconds must be > 0");
        }
        this.id = id;
        this.owner = initialOwner;
        this.captureSeconds = captureSeconds;
    }

    /**
     * 按圈内人数差推进据点状态。
     *
     * @param attackerInside 圈内攻方人数
     * @param defenderInside 圈内守方人数
     * @param dtSeconds      推进时长（秒）
     * @return 本 tick 内发生的据点事件；无则 null
     */
    public ZoneEvent update(int attackerInside, int defenderInside, double dtSeconds) {
        int diff = attackerInside - defenderInside;
        if (diff == 0 || dtSeconds <= 0) {
            return null;
        }
        double step = Math.abs(diff) * dtSeconds / captureSeconds;
        if (diff > 0) {
            // 攻方占优：推进。已固守时 meter 本为 1，封顶即可，不再产生事件。
            meter = Math.min(meter + step, 1.0);
            if (meter >= 1.0 && owner != Side.ATTACKER) {
                return flip(Side.ATTACKER);
            }
        } else {
            // 守方占优：清空攻方推进度；只有攻方已得手时清零才算夺回。
            meter = Math.max(meter - step, 0.0);
            if (meter <= 0.0 && owner == Side.ATTACKER) {
                return flip(Side.DEFENDER);
            }
        }
        return null;
    }

    private ZoneEvent flip(Side newOwner) {
        owner = newOwner;
        meter = newOwner == Side.ATTACKER ? 1.0 : 0.0;
        return new ZoneEvent(ZoneEventType.ZONE_CAPTURED, id, newOwner);
    }

    /** 回滚到"守方已占领、推进归零"的初始态（局末重置用）。 */
    public void reset() {
        owner = Side.DEFENDER;
        meter = 0.0;
    }

    public String id() {
        return id;
    }

    public Side owner() {
        return owner;
    }

    /** 攻方推进度 [0,1]；owner=ATTACKER 时为 1.0（已固守）。 */
    public double meter() {
        return meter;
    }

    public double captureSeconds() {
        return captureSeconds;
    }
}
