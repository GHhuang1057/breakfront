package com.breakfront.weapon;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 100HP / 20TPS 击杀合理性核验（任务 B4）。
 *
 * <p>默认武器数值不动；若断言失败说明数值偏离经验区间——此时应放宽断言区间，
 * 而非改动 WeaponCatalog 的默认数值（见任务说明「宁可断言放宽区间」）。
 * 区间比纯经验值略宽，兼容衰减远端。
 */
class WeaponTtkTest {

    private static WeaponSpec spec(String id) {
        return WeaponCatalog.byId(id).orElseThrow(() -> new AssertionError("missing " + id));
    }

    // ---- AR（突击步枪）：中距离约 4-6 发 ----
    @Test
    void arBtkWithinRange() {
        WeaponSpec hk = spec("hk416d");
        // 中距离 25m：躯干 6 发、爆头 4 发（经验区间 4-6）
        assertTrue(TtkMath.btkMax(hk, 25) >= 4 && TtkMath.btkMax(hk, 25) <= 7,
                "hk416d@25m 躯干 BTK 应≈4-6，实际 " + TtkMath.btkMax(hk, 25));
        assertTrue(TtkMath.btkMin(hk, 25) >= 3 && TtkMath.btkMin(hk, 25) <= 5,
                "hk416d@25m 爆头 BTK 应≈3-4，实际 " + TtkMath.btkMin(hk, 25));
        // 近距离 10m 不应超过 7 发
        assertTrue(TtkMath.btkMax(hk, 10) <= 7, "hk416d@10m 躯干 BTK 应≤7，实际 " + TtkMath.btkMax(hk, 10));

        WeaponSpec m4 = spec("m4a1");
        assertTrue(TtkMath.btkMax(m4, 25) >= 4 && TtkMath.btkMax(m4, 25) <= 7,
                "m4a1@25m 躯干 BTK 应≈4-6，实际 " + TtkMath.btkMax(m4, 25));
    }

    // ---- DMR（精确射手步枪）：约 3 发 ----
    @Test
    void dmrBtkThree() {
        WeaponSpec mk = spec("mk14");
        // 近距离 0m：34 伤 → 3 发
        assertTrue(TtkMath.btkMax(mk, 0) >= 2 && TtkMath.btkMax(mk, 0) <= 4,
                "mk14@0m 躯干 BTK 应≈3，实际 " + TtkMath.btkMax(mk, 0));
        // 远端 40m 仍应 ≤ 6
        assertTrue(TtkMath.btkMax(mk, 40) >= 3 && TtkMath.btkMax(mk, 40) <= 6,
                "mk14@40m 躯干 BTK 应≈3-4，实际 " + TtkMath.btkMax(mk, 40));
    }

    // ---- SR（狙击步枪）：1-2 发（爆头 1 发） ----
    @Test
    void srBtkOneToTwo() {
        WeaponSpec kar = spec("kar98");
        // 0m 躯干 76 伤 → 2 发；爆头 167 伤 → 1 发
        assertTrue(TtkMath.btkMax(kar, 0) >= 1 && TtkMath.btkMax(kar, 0) <= 3,
                "kar98@0m 躯干 BTK 应≈1-2，实际 " + TtkMath.btkMax(kar, 0));
        assertEquals(1, TtkMath.btkMin(kar, 0), "kar98@0m 爆头应 1 发");
        // 远端 100m 躯干仍 ≤ 4
        assertTrue(TtkMath.btkMax(kar, 100) >= 2 && TtkMath.btkMax(kar, 100) <= 4,
                "kar98@100m 躯干 BTK 应≈2-3，实际 " + TtkMath.btkMax(kar, 100));
    }

    // ---- 霰弹：2-3 发（壳） ----
    @Test
    void shotgunBtkTwoToThree() {
        WeaponSpec aa12 = spec("aa12"); // 70/壳，8 丸
        assertTrue(TtkMath.btkMax(aa12, 0) >= 2 && TtkMath.btkMax(aa12, 0) <= 3,
                "aa12@0m 应 2 壳，实际 " + TtkMath.btkMax(aa12, 0));
        assertTrue(TtkMath.btkMax(aa12, 16) >= 2 && TtkMath.btkMax(aa12, 16) <= 4,
                "aa12@16m(衰减端) 应 2-3 壳，实际 " + TtkMath.btkMax(aa12, 16));

        WeaponSpec m590 = spec("m590"); // 110/壳，10 丸
        assertTrue(TtkMath.btkMax(m590, 0) >= 1 && TtkMath.btkMax(m590, 0) <= 2,
                "m590@0m 应 1-2 壳，实际 " + TtkMath.btkMax(m590, 0));
    }

    // ---- SMG（冲锋枪）：约 5-7 发（ump45 当前 ~8 发，略宽以兼容不改默认值） ----
    @Test
    void smgBtkRange() {
        WeaponSpec ump = spec("ump45");
        // 经验 5-7；默认 ump45 近距离 8 发，故放宽至 [6,10] 而非改数值
        assertTrue(TtkMath.btkMax(ump, 0) >= 6 && TtkMath.btkMax(ump, 0) <= 10,
                "ump45@0m 躯干 BTK 应≈5-7（默认~8），实际 " + TtkMath.btkMax(ump, 0));
    }

    // ---- LMG（支援）：近距离约 7 发 ----
    @Test
    void lmgBtkRange() {
        WeaponSpec m249 = spec("m249");
        assertTrue(TtkMath.btkMax(m249, 0) >= 5 && TtkMath.btkMax(m249, 0) <= 9,
                "m249@0m 躯干 BTK 应≈7，实际 " + TtkMath.btkMax(m249, 0));
    }

    // ---- TTK 量级 sanity：AR 中距离应在数百毫秒级 ----
    @Test
    void ttkMagnitudeSane() {
        WeaponSpec hk = spec("hk416d");
        double ttk = TtkMath.ttkMaxMs(hk, 25);
        assertTrue(ttk >= 400 && ttk <= 900,
                "hk416d@25m TTK(含反应) 应数百毫秒，实际 " + ttk);
        // 爆头 TTK 应短于躯干 TTK
        assertTrue(TtkMath.ttkMinMs(hk, 25) < TtkMath.ttkMaxMs(hk, 25));
    }

    // ---- 工具基本属性：min<=max，ceil 向上 ----
    @Test
    void mathInvariants() {
        WeaponSpec kar = spec("kar98");
        assertTrue(TtkMath.btkMin(kar, 100) <= TtkMath.btkMax(kar, 100));
        // 伤害为 0 时无法击杀
        WeaponSpec zero = new WeaponSpec("zero", "ZERO", WeaponClass.AR,
                com.breakfront.weapon.AmmoType.CAL_556X45, com.breakfront.weapon.FireMode.AUTO,
                30, 180, 0, 1, 1.0, 0, 1, 1.0, 600, 1.0);
        assertEquals(Integer.MAX_VALUE, TtkMath.bulletsToKill(zero, 0, false));
    }
}
