package com.breakfront.weapon;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 武器库（骨架·静态目录）。
 *
 * <p>数值口径沿 100HP 调参批次（scripts/tune_tacz_pack.py 族参数）：
 * AR 20/17/13×1.6、LMG 15、SR 76×2.2、霰弹 aa12 70（8 丸分摊）等；
 * {@code damage} 一律为整发总伤，pellets&gt;1 运行时按丸分摊。
 *
 * <p>Kit 联动：{@link #KITS} 的 taczGun/taczAmmo 与服务器 Kits 发放使用的
 * 数据 id 保持一致（如 hk416d/556x45），供未来把 Kits 迁移到本目录驱动。
 */
public final class WeaponCatalog {

    private static final Map<String, WeaponSpec> SPECS = new LinkedHashMap<>();

    static {
        register(new WeaponSpec("hk416d", "HK416D", WeaponClass.AR, AmmoType.CAL_556X45,
                FireMode.AUTO, 30, 180, 20, 1, 1.6, 8, 45, 0.65, 700, 1.5));
        register(new WeaponSpec("m4a1", "M4A1", WeaponClass.AR, AmmoType.CAL_556X45,
                FireMode.AUTO, 30, 180, 20, 1, 1.6, 8, 45, 0.65, 720, 1.5));
        register(new WeaponSpec("ump45", "UMP45", WeaponClass.SMG, AmmoType.CAL_45ACP,
                FireMode.AUTO, 25, 150, 14, 1, 1.6, 6, 30, 0.7, 680, 1.3));
        register(new WeaponSpec("m249", "M249", WeaponClass.LMG, AmmoType.CAL_556X45,
                FireMode.AUTO, 75, 300, 15, 1, 1.5, 10, 55, 0.6, 750, 1.6));
        register(new WeaponSpec("mk14", "MK14 EBR", WeaponClass.DMR, AmmoType.CAL_762X39,
                FireMode.SEMI, 20, 80, 34, 1, 1.8, 12, 70, 0.6, 300, 3.0));
        register(new WeaponSpec("kar98", "Kar98k", WeaponClass.SR, AmmoType.CAL_792X57,
                FireMode.BOLT, 4, 40, 76, 1, 2.2, 20, 120, 0.55, 40, 6.0));
        register(new WeaponSpec("aa12", "AA-12", WeaponClass.SG, AmmoType.CAL_12GAUGE,
                FireMode.AUTO, 8, 48, 70, 8, 1.3, 4, 16, 0.55, 240, 1.2));
        register(new WeaponSpec("m590", "M590A1", WeaponClass.SG, AmmoType.CAL_12GAUGE,
                FireMode.PUMP, 6, 36, 110, 10, 1.3, 3, 14, 0.5, 60, 1.2));
        register(new WeaponSpec("glock17", "Glock 17", WeaponClass.PISTOL, AmmoType.CAL_9MM,
                FireMode.SEMI, 17, 68, 20, 1, 1.7, 6, 25, 0.75, 420, 1.1));
    }

    /** 兵种套件：weaponId 指向 {@link #SPECS}，taczGun/taczAmmo 供服务器发放指令使用。 */
    public record KitSpec(String kitId, String displayName,
                          String weaponId, String taczGun, String taczAmmo,
                          int spareAmmo) {
    }

    /** 与服务器 Kits.CLASSES 同序的兵种套件表。 */
    public static final List<KitSpec> KITS = List.of(
            new KitSpec("assault", "突击兵", "hk416d", "hk416d", "556x45", 180),
            new KitSpec("engineer", "工程兵", "aa12", "aa12", "12g", 48),
            new KitSpec("support", "支援兵", "m249", "m249", "556x45", 300),
            new KitSpec("recon", "侦察兵", "kar98", "kar98", "792x57", 40));

    private static void register(WeaponSpec spec) {
        SPECS.put(spec.id(), spec);
    }

    public static Optional<WeaponSpec> byId(String id) {
        return Optional.ofNullable(SPECS.get(id));
    }

    public static List<WeaponSpec> all() {
        return List.copyOf(SPECS.values());
    }

    /** 按兵种 id 查套件（未知兵种回退 assault）。 */
    public static KitSpec kit(String classId) {
        return KITS.stream().filter(k -> k.kitId().equals(classId))
                .findFirst().orElse(KITS.get(0));
    }

    private WeaponCatalog() {
    }
}
