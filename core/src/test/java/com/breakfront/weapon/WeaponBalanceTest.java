package com.breakfront.weapon;

import java.util.Locale;

/**
 * 武器数值回归测试（2026-09-10 v1）：把 BF2042 TTK/BTK 基准写成断言，防止调参回退。
 *
 * <p>基准来源：Game8 BF2042 BTK/TTK 实测表（TTK=(60/RPM)×(BTK−1)）+ Sym 数据挖掘。
 * 断言口径：
 * <ul>
 *   <li>AR：15m 内 4 发击杀、TTK ≤ 300ms；60m 内 ≤ 6 发</li>
 *   <li>SMG：15m 内 ≤ 6 发、TTK ≤ 350ms（近距优势）；30m 后明显跌档</li>
 *   <li>LMG：15m 内 ≤ 5 发、TTK ≤ 380ms；远距衰减小（60m ≤ 6 发）</li>
 *   <li>DMR：15m 内 2 发、TTK ≤ 200ms</li>
 *   <li>SR：躯干 1 发致死（此为本项目 SR 身份的硬要求）</li>
 *   <li>霰弹：18m 内 1 壳致死（m590）/ 2 壳内（aa12）</li>
 *   <li>手枪：15m 内 ≤ 5 发</li>
 * </ul>
 */
public final class WeaponBalanceTest {

    private static int pass = 0, fail = 0;

    public static void main(String[] args) {
        testAssaultRifles();
        testSmg();
        testLmg();
        testDmr();
        testSniper();
        testShotguns();
        testPistol();
        testCrossClassMonotonicity();
        testHeadshotAlwaysHelps();

        System.out.println("---- WeaponBalanceTest: " + pass + " passed, " + fail + " failed ----");
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void testAssaultRifles() {
        for (String id : new String[]{"hk416d", "m4a1"}) {
            WeaponSpec s = spec(id);
            int btk15 = TtkMath.bulletsToKill(s, 15, false);
            int btk60 = TtkMath.bulletsToKill(s, 60, false);
            double ttk15 = TtkMath.ttkMs(s, 15, false, 0);
            check(id + " AR 15m ≤4发（实际 " + btk15 + "）", btk15 <= 4);
            check(id + " AR 15m TTK ≤300ms（实际 " + f(ttk15) + "）", ttk15 <= 300);
            check(id + " AR 60m ≤6发（实际 " + btk60 + "）", btk60 <= 6);
        }
    }

    private static void testSmg() {
        WeaponSpec s = spec("ump45");
        int btk15 = TtkMath.bulletsToKill(s, 15, false);
        int btk30 = TtkMath.bulletsToKill(s, 30, false);
        double ttk15 = TtkMath.ttkMs(s, 15, false, 0);
        check("ump45 SMG 15m ≤6发（实际 " + btk15 + "）", btk15 <= 6);
        check("ump45 SMG 15m TTK ≤350ms（实际 " + f(ttk15) + "）", ttk15 <= 350);
        // 近距应明显优于远距（衰减体现身份）
        check("ump45 SMG 30m 比 15m 更多弹（" + btk30 + " > " + btk15 + "）", btk30 > btk15);
    }

    private static void testLmg() {
        WeaponSpec s = spec("m249");
        int btk15 = TtkMath.bulletsToKill(s, 15, false);
        int btk60 = TtkMath.bulletsToKill(s, 60, false);
        double ttk15 = TtkMath.ttkMs(s, 15, false, 0);
        check("m249 LMG 15m ≤5发（实际 " + btk15 + "）", btk15 <= 5);
        check("m249 LMG 15m TTK ≤380ms（实际 " + f(ttk15) + "）", ttk15 <= 380);
        check("m249 LMG 远距衰减小 60m ≤6发（实际 " + btk60 + "）", btk60 <= 6);
    }

    private static void testDmr() {
        WeaponSpec s = spec("mk14");
        int btk15 = TtkMath.bulletsToKill(s, 15, false);
        double ttk15 = TtkMath.ttkMs(s, 15, false, 0);
        check("mk14 DMR 15m 2发（实际 " + btk15 + "）", btk15 == 2);
        check("mk14 DMR 15m TTK ≤200ms（实际 " + f(ttk15) + "）", ttk15 <= 200);
        check("mk14 DMR 60m ≤3发（实际 " + TtkMath.bulletsToKill(s, 60, false) + "）",
                TtkMath.bulletsToKill(s, 60, false) <= 3);
    }

    private static void testSniper() {
        WeaponSpec s = spec("kar98");
        check("kar98 SR 躯干一枪致死（伤 " + f(s.damage()) + " ≥100）", s.damage() >= TtkMath.TARGET_HP);
        check("kar98 SR 60m 仍一枪致死（伤 " + f(s.totalDamageAt(60)) + "）",
                s.totalDamageAt(60) >= TtkMath.TARGET_HP);
        check("kar98 SR 爆头必杀", s.headshotTotalAt(60) >= TtkMath.TARGET_HP);
        check("kar98 SR 200m 不致死（远距应有衰减，伤 " + f(s.totalDamageAt(200)) + "）",
                s.totalDamageAt(200) < TtkMath.TARGET_HP);
    }

    private static void testShotguns() {
        WeaponSpec pump = spec("m590");
        check("m590 泵动 18m 内 1 壳致死（15m 伤 " + f(pump.totalDamageAt(15)) + "）",
                pump.totalDamageAt(15) >= TtkMath.TARGET_HP);
        check("m590 30m 后需 ≥2 壳（伤 " + f(pump.totalDamageAt(30)) + "）",
                pump.totalDamageAt(30) < TtkMath.TARGET_HP);
        WeaponSpec auto = spec("aa12");
        check("aa12 自动霰弹满伤区 1 壳致死（伤 " + f(auto.totalDamageAt(auto.falloffStart())) + "）",
                auto.totalDamageAt(auto.falloffStart()) >= TtkMath.TARGET_HP);
        check("aa12 15m 起需 2 壳（伤 " + f(auto.totalDamageAt(15)) + "）",
                auto.totalDamageAt(15) < TtkMath.TARGET_HP);
        // 泵动换致死距离：泵动的 1 壳必杀距离应远于全自动
        check("泵动致死距离 > 全自动（" + pump.falloffStart() + " > " + auto.falloffStart() + "）",
                pump.falloffStart() > auto.falloffStart());
    }

    private static void testPistol() {
        WeaponSpec s = spec("glock17");
        int btk15 = TtkMath.bulletsToKill(s, 15, false);
        check("glock17 手枪 15m ≤5发（实际 " + btk15 + "）", btk15 <= 5);
    }

    /** 族间单调性：近距 TTK 应满足 SG/DMR/SR ≤ AR/SMG ≤ LMG ≤ PISTOL。 */
    private static void testCrossClassMonotonicity() {
        double ar = TtkMath.ttkMs(spec("hk416d"), 15, false, 0);
        double pistol = TtkMath.ttkMs(spec("glock17"), 15, false, 0);
        double lmg = TtkMath.ttkMs(spec("m249"), 15, false, 0);
        check("近距 TTK：AR(" + f(ar) + ") < 手枪(" + f(pistol) + ")", ar < pistol);
        check("近距 TTK：LMG(" + f(lmg) + ") ≥ AR(" + f(ar) + ")", lmg >= ar - 1);
    }

    /** 爆头必须带来收益（弹数不增，TTK 不增）。 */
    private static void testHeadshotAlwaysHelps() {
        for (WeaponSpec s : WeaponCatalog.all()) {
            for (double m : new double[]{5, 15, 30, 60}) {
                int body = TtkMath.bulletsToKill(s, m, false);
                int head = TtkMath.bulletsToKill(s, m, true);
                if (head > body) {
                    check(s.id() + " 爆头弹数不应多于躯干 @" + m + "m", false);
                    return;
                }
                double tb = TtkMath.ttkMs(s, m, false, 0);
                double th = TtkMath.ttkMs(s, m, true, 0);
                if (th > tb + 0.01) {
                    check(s.id() + " 爆头 TTK 不应慢于躯干 @" + m + "m", false);
                    return;
                }
            }
        }
        check("全部武器爆头均有正收益", true);
    }

    // ---- 工具 ----

    private static WeaponSpec spec(String id) {
        return WeaponCatalog.byId(id).orElseThrow(() -> new AssertionError("武器缺失: " + id));
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }

    private static void check(String name, boolean cond) {
        if (cond) {
            pass++;
        } else {
            fail++;
            System.out.println("[FAIL] " + name);
        }
    }

    private WeaponBalanceTest() {
    }
}
