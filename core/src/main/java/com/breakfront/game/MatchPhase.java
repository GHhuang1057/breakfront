package com.breakfront.game;

/** 对局阶段（回合状态机）。 */
public enum MatchPhase {
    LOBBY("大厅"),
    COUNTDOWN("部署倒计时"),
    BATTLE("战斗中"),
    ROUND_END("结算"),
    RESET("战场重置");

    public final String labelCn;

    MatchPhase(String labelCn) {
        this.labelCn = labelCn;
    }
}
