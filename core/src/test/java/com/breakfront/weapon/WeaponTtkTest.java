package com.breakfront.weapon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 100HP / 20TPS 击杀合理性核验。
 *
 * <p><b>2026-09-10 更新</b>：数值基准由「经验区间」升级为 <b>BF2042 TTK/BTK 实测基准</b>
 * （Game8 数据表 + Sym 数据挖掘，TTK=(60/RPM)×(BTK−1)）。本次同步按新数值迁移断言：
 * <ul>
 *   <li>AR：近距/中距 4 发、远距 6 发</li>
 *   <li>DMR：2 发（原 3 发）</li>
 *   <li>SR：躯干 1 发致死（原 2 发）—— 狙击手身份的硬要求</li>
 *   <li>霰弹：满伤区 1 壳致死（原 2-3 壳）</li>
 *   <li>SMG：近距 6 发（原 8 发）</li>
 *   <li>LMG：近距 5 发（原 7 发）</li>
 * </ul>
 * 设计意图与完整数值表见 docs/weapon-balance-2026-09-10.md；
 * 区间型回归由 {@link WeaponBalanceTest} 守住。
 */
class WeaponTtkTest {

    private static WeaponSpec spec(String id) {
        return WeaponCatalog.byId(id).orElseThrow(() -> new AssertionError("missing " + id));
    }

    // ---- AR（突击步枪）：近距 4 发、中距 4-5 发 ----
    @Test
    void arBtkWithinRange() {
        WeaponSpec hk = spec("hk416d");
        assertTrue(TtkMath.btkMax(hk, 25) >= 3 && TtkMath.btkMax(hk, 25) <= 5,
                "hk416d@25m 躯干 BTK 应≈4，实际 " + TtkMath.btkMax(hk, 25));
        assertTrue(TtkMath.btkMin(hk, 25) >= 2 && TtkMath.btkMin(hk, 25) <= 4,
                "hk416d@25m 爆头 BTK 应≈3，实际 " + TtkMath.btkMin(hk, 25));
        assertTrue(TtkMath.btkMax(hk, 10) <= 5,
                "hk416d@10m 躯干 BTK 应≤5，实际 " + TtkMath.btkMax(hk, 10));

        WeaponSpec m4 = spec("m4a1");
        assertTrue(TtkMath.btkMax(m4, 25) >= 3 && TtkMath.btkMax(m4, 25) <= 5,
                "m4a1@25m 躯干 BTK 应≈4，实际 " + TtkMath.btkMax(m4, 25));
    }

    // ---- DMR（精确射手步枪）：2 发 ----
    @Test
    void dmrBtkTwo() {
        WeaponSpec mk = spec("mk14");
        assertEquals(2, TtkMath.btkMax(mk, 0), "mk14@0m 躯干应 2 发");
        assertTrue(TtkMath.btkMax(mk, 40) >= 2 && TtkMath.btkMax(mk, 40) <= 3,
                "mk14@40m 躯干 BTK 应≈2-3，实际 " + TtkMath.btkMax(mk, 40));
    }

    // ---- SR（狙击步枪）：躯干 1 发致死，爆头亦然 ----
    @Test
    void srBtkOneShotBody() {
        WeaponSpec kar = spec("kar98");
        assertEquals(1, TtkMath.btkMax(kar, 0), "kar98@0m 躯干应一枪致死");
        assertEquals(1, TtkMath.btkMin(kar, 0), "kar98@0m 爆头应一枪致死");
        assertEquals(1, TtkMath.btkMax(kar, 100), "kar98@100m 躯干应仍一枪");
        assertTrue(TtkMath.btkMax(kar, 200) >= 1 && TtkMath.btkMax(kar, 200) <= 2,
                "kar98@200m 应 1-2 枪，实际 " + TtkMath.btkMax(kar, 200));
    }

    // ---- 霰弹：满伤区 1 壳致死 ----
    @Test
    void shotgunOneShellInFullDamageRange() {
        WeaponSpec aa12 = spec("aa12");
        assertEquals(1, TtkMath.btkMax(aa12, 0), "aa12@0m 应 1 壳致死");
        assertEquals(1, TtkMath.btkMax(aa12, aa12.falloffStart()), "aa12 满伤区边缘应仍 1 壳");
        assertEquals(2, TtkMath.btkMax(aa12, 16), "aa12@16m 应 2 壳");

        WeaponSpec m590 = spec("m590");
        assertEquals(1, TtkMath.btkMax(m590, 0), "m590@0m 应 1 壳致死");
        assertEquals(1, TtkMath.btkMax(m590, 15), "m590@15m（满伤区内）应仍 1 壳");
    }

    // ---- SMG（冲锋枪）：近距 6 发，衰减明显 ----
    @Test
    void smgBtkRange() {
        WeaponSpec ump = spec("ump45");
        assertEquals(6, TtkMath.btkMax(ump, 0), "ump45@0m 躯干应 6 发");
        assertTrue(TtkMath.btkMax(ump, 30) > TtkMath.btkMax(ump, 0),
                "ump45 应在 30m 后跌档，实际 " + TtkMath.btkMax(ump, 30));
    }

    // ---- LMG（支援）：近距离 5 发，远距衰减小 ----
    @Test
    void lmgBtkRange() {
        WeaponSpec m249 = spec("m249");
        assertEquals(5, TtkMath.btkMax(m249, 0), "m249@0m 躯干应 5 发");
        assertTrue(TtkMath.btkMax(m249, 60) <= 7,
                "m249 远距衰减小，60m 应 ≤7 发，实际 " + TtkMath.btkMax(m249, 60));
    }

    // ---- TTK 量级：AR 中距离对齐 BF2042 优秀档 ----
    @Test
    void ttkMagnitudeSane() {
        WeaponSpec hk = spec("hk416d");
        double ttk = TtkMath.ttkMaxMs(hk, 25);      // 含 200ms 反应常数
        assertTrue(ttk >= 350 && ttk <= 700,
                "hk416d@25m TTK(含反应) 应数百毫秒，实际 " + ttk);
        assertTrue(TtkMath.ttkMinMs(hk, 25) < TtkMath.ttkMaxMs(hk, 25), "爆头 TTK 应短于躯干");
        assertTrue(TtkMath.ttkMs(hk, 25, false, 0) <= 300,
                "hk416d@25m 裸 TTK 应≤300ms，实际 " + TtkMath.ttkMs(hk, 25, false, 0));
    }

    // ---- 工具基本属性：min<=max，ceil 向上 ----
    @Test
    void mathInvariants() {
        WeaponSpec kar = spec("kar98");
        assertTrue(TtkMath.btkMin(kar, 100) <= TtkMath.btkMax(kar, 100));
        // 伤害为 0 时无法击杀
        WeaponSpec zero = new WeaponSpec("zero", "ZERO", WeaponClass.AR,
                AmmoType.CAL_556X45, FireMode.AUTO,
                30, 180, 0, 1, 1.0, 0, 1, 1.0, 600, 1.0);
        assertEquals(Integer.MAX_VALUE, TtkMath.bulletsToKill(zero, 0, false));
    }
}
