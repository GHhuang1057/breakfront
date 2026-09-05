package com.breakfront.weapon;

/**
 * 100HP 体系的击杀数 / 击杀用时推算工具（纯 Java，无 Minecraft 依赖，便于单测）。
 *
 * <p>口径对齐 {@link WeaponSpec}：{@code damage} = 整发总伤（霰弹 pellets&gt;1 时由
 * {@link WeaponSpec#totalDamageAt(double)} 按整发计，故这里「1 发」= 扣一次扳机的整发/整壳，
 * 霰弹的「发」即一壳 = 所有弹丸之和）。爆头用 {@link WeaponSpec#headshotTotalAt(double)}。
 *
 * <p>经验区间（任务 B4 验收）：AR 中距离 4-6 发、DMR 3 发、SR 1-2 发、霰弹 2-3 发、
 * SMG 5-7 发。数值以 WeaponCatalog 默认值为准，本工具只做推算，不改任何武器数值。
 */
public final class TtkMath {

    /** 目标血量（BREAKFRONT 100HP 体系）。 */
    public static final double TARGET_HP = 100.0;

    /** 反应常数：从「发现敌人」到「开出第一发」的经验延迟（ms），TTK 估算统一加上。 */
    public static final double REACTION_MS = 200.0;

    private TtkMath() {
    }

    /** 单发有效伤害（按是否爆头）。 */
    public static double shotDamage(WeaponSpec spec, double meters, boolean headshot) {
        return headshot ? spec.headshotTotalAt(meters) : spec.totalDamageAt(meters);
    }

    /** 打空 100HP 目标所需子弹数（向上取整）；dmg≤0 视为无法击杀，返回 {@link Integer#MAX_VALUE}。 */
    public static int bulletsToKill(WeaponSpec spec, double meters, boolean headshot) {
        double dmg = shotDamage(spec, meters, headshot);
        if (dmg <= 0) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.ceil(TARGET_HP / dmg);
    }

    /** 击杀用时 ms：反应常数 + (弹数-1) × 射击间隔（rpm 决定）。 */
    public static double ttkMs(WeaponSpec spec, double meters, boolean headshot, double reactionMs) {
        int bt = bulletsToKill(spec, meters, headshot);
        if (bt <= 0) {
            return reactionMs;
        }
        return reactionMs + (bt - 1) * spec.shotIntervalMs();
    }

    /** 最乐观（全程爆头）击杀子弹数。 */
    public static int btkMin(WeaponSpec spec, double meters) {
        return bulletsToKill(spec, meters, true);
    }

    /** 最保守（全程躯干）击杀子弹数。 */
    public static int btkMax(WeaponSpec spec, double meters) {
        return bulletsToKill(spec, meters, false);
    }

    /** 最乐观 TTK（ms，含反应常数）。 */
    public static double ttkMinMs(WeaponSpec spec, double meters) {
        return ttkMs(spec, meters, true, REACTION_MS);
    }

    /** 最保守 TTK（ms，含反应常数）。 */
    public static double ttkMaxMs(WeaponSpec spec, double meters) {
        return ttkMs(spec, meters, false, REACTION_MS);
    }
}
