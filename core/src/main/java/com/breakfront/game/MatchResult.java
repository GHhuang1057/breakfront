package com.breakfront.game;

/** 对局结果。 */
public enum MatchResult {
    NONE("未结束"),
    ATTACKER_WIN("攻方获胜"),
    DEFENDER_WIN("守方获胜");

    public final String labelCn;

    MatchResult(String labelCn) {
        this.labelCn = labelCn;
    }
}
