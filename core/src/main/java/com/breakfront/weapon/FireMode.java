package com.breakfront.weapon;

/**
 * 开火模式（骨架）。
 *
 * <p>burst 档位以 {@link #burstRounds} 表达每扣一次的连发数（AUTO/BOLT 等为 0 或 1）。
 */
public enum FireMode {

    AUTO(0),
    BURST(3),
    SEMI(1),
    PUMP(1),
    BOLT(1),
    SINGLE(1);

    private final int burstRounds;

    FireMode(int burstRounds) {
        this.burstRounds = burstRounds;
    }

    /** 每扣一次扳机射出的弹数（AUTO=0 表示按住持续）。 */
    public int burstRounds() {
        return burstRounds;
    }

    public boolean isAutomatic() {
        return this == AUTO;
    }
}
