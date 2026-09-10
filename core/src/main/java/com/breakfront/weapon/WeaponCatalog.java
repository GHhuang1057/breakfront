package com.breakfront.weapon;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 武器库（骨架·静态目录）。
 *
 * <p>数值口径沿 100HP 调参批次（scripts/tune_tacz_pack.py 族参数）。
 * {@code damage} 一律为整发总伤，pellets&gt;1 运行时按丸分摊。
 *
 * <p><b>2026-09-10 TTK/BTK 重标</b>：原表全面偏弱（AR 近距 429ms、SMG 706ms、DMR 600ms、
 * SR 两枪 1500ms），距 BF2042 基准 1.5–3 倍。本次按 BF2042 伤害模型重定，
 * 基准（Game8 实测 + Sym 数据挖掘，TTK=(60/RPM)×(BTK−1)）：
 * <pre>
 *   顶级 200-223ms | 优秀 240-270ms | 中档 267-300ms | 偏低 356-401ms
 *   AR 近距 4-5 发 / 中距 5 发 / 远距 5-6 发（有效射程内保持击杀数）
 *   SMG 近距 5-6 发｜DMR 2-3 发｜SR 1 发（躯干即死）｜霰弹近距 1 壳
 * </pre>
 * 设计要点：AR 基础伤 26（近距 4 发=104）；SMG 高射速低单伤、衰减快；
 * DMR 50（2 发）；SR 105（躯干一枪）；霰弹按壳计，全自动 12m 内 1 壳、泵动 18m 内 1 壳。
 */
public final class WeaponCatalog {

    private static final Map<String, WeaponSpec> SPECS = new LinkedHashMap<>();

    /**
     * 兵种 → 可选主武器白名单（任务 B：部署屏兵种/武器选择用）。
     * 取值指向 {@link #SPECS} 的 id；未知 gunId 在 Kits/指令层回退该兵种默认枪。
     */
    private static final Map<String, List<String>> CLASS_GUNS = new LinkedHashMap<>();

    static {
        CLASS_GUNS.put("assault", List.of("hk416d", "m4a1"));
        CLASS_GUNS.put("engineer", List.of("aa12", "m590"));
        CLASS_GUNS.put("support", List.of("m249", "m4a1"));
        CLASS_GUNS.put("recon", List.of("kar98", "mk14"));
    }

    static {
        // ---- 突击步枪 AR：全能主力，近距 4 发、远距 6 发 ----
        register(new WeaponSpec("hk416d", "HK416D", WeaponClass.AR, AmmoType.CAL_556X45,
                FireMode.AUTO, 30, 180, 26, 1, 1.55, 20, 70, 0.62, 750, 1.5));
        register(new WeaponSpec("m4a1", "M4A1", WeaponClass.AR, AmmoType.CAL_556X45,
                FireMode.AUTO, 30, 180, 25, 1, 1.55, 22, 72, 0.62, 800, 1.5));

        // ---- 冲锋枪 SMG：近距霸主，高射速低单伤、30m 后迅速跌档 ----
        register(new WeaponSpec("ump45", "UMP45", WeaponClass.SMG, AmmoType.CAL_45ACP,
                FireMode.AUTO, 25, 150, 18, 1, 1.6, 12, 36, 0.5, 950, 1.3));

        // ---- 轻机枪 LMG：持续火力，单发中等、远距衰减小 ----
        register(new WeaponSpec("m249", "M249", WeaponClass.LMG, AmmoType.CAL_556X45,
                FireMode.AUTO, 75, 300, 22, 1, 1.45, 25, 90, 0.68, 700, 1.6));

        // ---- 精确射手步枪 DMR：2-3 发，中远距压制 ----
        register(new WeaponSpec("mk14", "MK14 EBR", WeaponClass.DMR, AmmoType.CAL_762X39,
                FireMode.SEMI, 20, 50, 50, 1, 1.8, 30, 110, 0.7, 380, 3.0));

        // ---- 狙击枪 SR：躯干一枪致死（105>100），爆头必杀 ----
        register(new WeaponSpec("kar98", "Kar98k", WeaponClass.SR, AmmoType.CAL_792X57,
                FireMode.BOLT, 4, 50, 105, 1, 2.0, 60, 200, 0.85, 45, 6.0));

        // ---- 霰弹枪 SG：近距 1 壳致死，远距急剧衰减（falloffEnd 后按丸分摊几乎无效）----
        // aa12：全自动，12m 满伤区内 1 壳必杀（射速换致死距离）
        register(new WeaponSpec("aa12", "AA-12", WeaponClass.SG, AmmoType.CAL_12GAUGE,
                FireMode.AUTO, 8, 80, 104, 8, 1.3, 12, 24, 0.40, 300, 1.2));
        // m590：泵动，18m 内 1 壳必杀（低射速换更远致死距离与更高单壳伤）
        register(new WeaponSpec("m590", "M590A1", WeaponClass.SG, AmmoType.CAL_12GAUGE,
                FireMode.PUMP, 6, 80, 130, 10, 1.3, 18, 30, 0.40, 90, 1.2));

        // ---- 手枪 PISTOL：副武器，近距 5 发、有爆头回报 ----
        register(new WeaponSpec("glock17", "Glock 17", WeaponClass.PISTOL, AmmoType.CAL_9MM,
                FireMode.SEMI, 17, 68, 22, 1, 1.7, 12, 45, 0.6, 500, 1.1));
    }

    /** 兵种套件：weaponId 指向 {@link #SPECS}，taczGun/taczAmmo 供服务器发放指令使用。 */
    public record KitSpec(String kitId, String displayName,
                          String weaponId, String taczGun, String taczAmmo,
                          int spareAmmo) {
    }

    /** 与服务器 Kits.CLASSES 同序的兵种套件表。 */
    public static final List<KitSpec> KITS = List.of(
            new KitSpec("assault", "突击兵", "hk416d", "hk416d", "556x45", 180),
            new KitSpec("engineer", "工程兵", "aa12", "aa12", "12g", 80),
            new KitSpec("support", "支援兵", "m249", "m249", "556x45", 300),
            new KitSpec("recon", "侦察兵", "kar98", "kar98", "792x57", 50));

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

    /** 兵种可选主武器白名单（gun id 列表，按推荐顺序）。 */
    public static List<String> classGuns(String classId) {
        return CLASS_GUNS.getOrDefault(classId, List.of());
    }

    /** 某枪是否属于某兵种白名单。 */
    public static boolean isGunAllowed(String classId, String gunId) {
        return gunId != null && CLASS_GUNS.getOrDefault(classId, List.of()).contains(gunId);
    }

    /** 兵种默认枪（白名单首把；未知兵种回退 hk416d）。 */
    public static String defaultGun(String classId) {
        List<String> g = CLASS_GUNS.get(classId);
        return (g == null || g.isEmpty()) ? "hk416d" : g.get(0);
    }

    private WeaponCatalog() {
    }
}
