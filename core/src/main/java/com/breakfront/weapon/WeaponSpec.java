package com.breakfront.weapon;

/**
 * 武器规格（骨架·不可变数据）。
 *
 * <p>数值语义对齐 100HP 体系调参结论（scripts/tune_tacz_pack.py）：
 * <ul>
 *   <li>{@code damage} = <b>整发总伤</b>（霰弹等 pellets&gt;1 时运行时按弹丸分摊，见
 *       {@link #pelletDamageAt(double)}）；</li>
 *   <li>{@code headshotMult} = 爆头倍率；</li>
 *   <li>伤害随距离线性衰减：{@code falloffStart} 内全额 → {@code falloffEnd} 降到
 *       {@code damage * minRatio}。</li>
 * </ul>
 */
public record WeaponSpec(
        String id,
        String displayName,
        WeaponClass weaponClass,
        AmmoType ammo,
        FireMode fireMode,
        int magazineSize,
        int reserveAmmo,
        int damage,
        int pellets,
        double headshotMult,
        int falloffStart,
        int falloffEnd,
        double minDamageRatio,
        int rpm,
        double adsZoom
) {

    /** 整发伤害在距离 meters(m) 处经衰减后的值（pellets 无关）。 */
    public double totalDamageAt(double meters) {
        if (meters <= falloffStart) {
            return damage;
        }
        if (meters >= falloffEnd) {
            return damage * minDamageRatio;
        }
        double t = (meters - falloffStart) / (falloffEnd - falloffStart);
        return damage * (1.0 - (1.0 - minDamageRatio) * t);
    }

    /** 单发弹丸伤害 = 整发衰减伤 / pellets（与 TaCZ 霰弹运行时分摊一致）。 */
    public double pelletDamageAt(double meters) {
        int p = Math.max(1, pellets);
        return totalDamageAt(meters) / p;
    }

    /** 爆头整发伤害（同一距离）。 */
    public double headshotTotalAt(double meters) {
        return totalDamageAt(meters) * headshotMult;
    }

    /** 理论射速间隔 ms（AUTO 连续射击节拍用）。 */
    public double shotIntervalMs() {
        return 60_000.0 / Math.max(1, rpm);
    }

    /** 简短存档键（管理台/指令引用）。 */
    public String key() {
        return id;
    }
}
