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
        Optional<WeaponSpec> hk = WeaponCatalog.byId("hk416d");
        assertTrue(hk.isPresent());
        WeaponSpec ar = hk.get();
        assertEquals(20, ar.totalDamageAt(0), 1e-6);
        assertEquals(20 * ar.minDamageRatio(), ar.totalDamageAt(ar.falloffEnd()), 1e-6);
        assertTrue(ar.totalDamageAt(10) > ar.totalDamageAt(30));
        assertTrue(ar.totalDamageAt(100) < ar.totalDamageAt(0));

        WeaponSpec aa12 = WeaponCatalog.byId("aa12").orElseThrow();
        assertEquals(8, aa12.pellets());
        assertEquals(70.0 / 8, aa12.pelletDamageAt(0), 1e-6);
        assertEquals(70.0, aa12.pelletDamageAt(0) * aa12.pellets(), 1e-6);
    }

    @Test
    void headshotMultiplierApplies() {
        WeaponSpec kar = WeaponCatalog.byId("kar98").orElseThrow();
        assertEquals(76 * 2.2, kar.headshotTotalAt(0), 1e-6);
        // SR 一发爆头在 100HP 体系应可击杀
        assertTrue(kar.headshotTotalAt(0) >= 100);
    }
}
