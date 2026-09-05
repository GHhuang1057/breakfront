package com.breakfront.game;

/** 攻防模式的默认调参（后续由地图配置 JSON 覆盖，见 charter §4.3）。 */
public final class BreakthroughTuning {

    /** 战斗血量体系：所有人（真人 + NPC）满血 = 100（原版 20 心 ×5 细化）。 */
    public static final double PLAYER_MAX_HEALTH = 100.0;

    /** 攻方初始部署资源（人票）。 */
    public static final int DEFAULT_ATTACKER_TICKETS = 250;
    /** 整场对局时长（秒）。 */
    public static final double DEFAULT_MATCH_SECONDS = 1500.0;
    /** 开打前部署倒计时（秒）。 */
    public static final double DEFAULT_COUNTDOWN_SECONDS = 30.0;
    /** 据点满表争夺所需秒数（人数优势 1v0 基线）。 */
    public static final double DEFAULT_ZONE_CAPTURE_SECONDS = 10.0;

    private BreakthroughTuning() {
    }
}
