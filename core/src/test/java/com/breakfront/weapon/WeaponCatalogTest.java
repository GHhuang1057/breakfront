package com.breakfront.weapon;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeaponCatalogTest {

    @Test
    void catalogHasKitGunsAndExtras() {
        assertTrue(WeaponCatalog.byId("hk416d").isPresent());
        assertTrue(WeaponCatalog.byId("aa12").isPresent());
        assertTrue(WeaponCatalog.byId("m249").isPresent());
        assertTrue(WeaponCatalog.byId("kar98").isPresent());
        assertTrue(WeaponCatalog.all().size() >= 8);
    }

    @Test
    void kitTableLinksToServerKitsIds() {
        WeaponCatalog.KitSpec assault = WeaponCatalog.kit("assault");
        assertEquals("hk416d", assault.taczGun());
        assertEquals("556x45", assault.taczAmmo());
        assertEquals("aa12", WeaponCatalog.kit("engineer").taczGun());
        assertEquals("m249", WeaponCatalog.kit("support").taczGun());
        assertEquals("kar98", WeaponCatalog.kit("recon").taczGun());
        // 未知兵种回退突击兵
        assertEquals("hk416d", WeaponCatalog.kit("no_such_class").taczGun());
    }

    @Test
    void damageFalloffIsMonotonicAndShotgunSplitsPerPellet() {
        WeaponSpec ar = WeaponCatalog.byId("hk416d").orElseThrow();
        // 满伤 = damage 字段；衰减终点 = damage × minDamageRatio
        // （语义化断言：不冻结具体数值，数值调整由 WeaponBalanceTest 守住设计区间）
        assertEquals(ar.damage(), ar.totalDamageAt(0), 1e-6);
        assertEquals(ar.damage() * ar.minDamageRatio(), ar.totalDamageAt(ar.falloffEnd()), 1e-6);
        assertTrue(ar.totalDamageAt(10) > ar.totalDamageAt(30), "衰减应单调递减");
        assertTrue(ar.totalDamageAt(100) < ar.totalDamageAt(0));

        WeaponSpec aa12 = WeaponCatalog.byId("aa12").orElseThrow();
        assertEquals(8, aa12.pellets());
        // 霰弹：整发总伤由 pellets 均分（运行时 applyShotgunDamageSpread 按此分摊）
        assertEquals(aa12.damage() / aa12.pellets(), aa12.pelletDamageAt(0), 1e-6);
        assertEquals(aa12.damage(), aa12.pelletDamageAt(0) * aa12.pellets(), 1e-6);
    }

    @Test
    void headshotMultiplierApplies() {
        WeaponSpec kar = WeaponCatalog.byId("kar98").orElseThrow();
        assertEquals(kar.damage() * kar.headshotMult(), kar.headshotTotalAt(0), 1e-6);
        // SR 爆头必杀（100HP 体系）
        assertTrue(kar.headshotTotalAt(0) >= 100, "SR 爆头应可一枪击杀");
        // SR 躯干亦应一枪致死 —— 狙击手身份的硬要求（BF2042 基准）
        assertTrue(kar.damage() >= TtkMath.TARGET_HP, "SR 躯干应一枪致死");
    }

    /** 全部武器：爆头伤害恒 > 躯干伤害（倍率字段语义正确）。 */
    @Test
    void headshotAlwaysExceedsBody() {
        for (WeaponSpec s : WeaponCatalog.all()) {
            assertTrue(s.headshotMult() > 1.0, s.id() + " 爆头倍率应 >1");
            assertTrue(s.headshotTotalAt(0) > s.totalDamageAt(0), s.id() + " 爆头伤害应高于躯干");
        }
    }
}
