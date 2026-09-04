package com.breakfront.game;

/** 阵营。 */
public enum Side {
    ATTACKER("攻方"),
    DEFENDER("守方");

    public final String labelCn;

    Side(String labelCn) {
        this.labelCn = labelCn;
    }
}
