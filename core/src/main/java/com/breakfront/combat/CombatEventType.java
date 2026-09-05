package com.breakfront.combat;

/**
 * 战斗事件类型（#47，对齐 GD656Killicon 计分事件思想）。
 *
 * <p>服务端权威事件源；每个类型有 BF 语境默认加分（可经 {@link BonusTable}
 * 由管理员覆盖）。爆头等「修饰」不单独发事件，而是由击杀事件携带 headshot 标志，
 * 由消费端决定是否叠加 {@link #HEADSHOT} 分（与 656 的 BonusEngine 拆分类似）。
 */
public enum CombatEventType {

    KILL("击杀"),
    HEADSHOT("爆头"),
    ASSIST("助攻"),
    CAPTURE("占领据点"),
    LOSS("据点失守"),
    SPOT("标记敌方"),
    SUPPRESS("压制"),
    VEHICLE_KILL("摧毁载具"),
    VEHICLE_DESTROYED("载具被毁"),
    REVIVE("救起队友"),
    MULTIKILL("连杀奖励");

    private final String label;

    CombatEventType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 属性配置键（scoring.&lt;key&gt;）。 */
    public String propKey() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
