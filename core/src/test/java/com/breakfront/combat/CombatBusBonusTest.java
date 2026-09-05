package com.breakfront.combat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatBusBonusTest {

    @BeforeEach
    void clean() {
        CombatBus.clear();
    }

    @Test
    void busDeliversAndCanUnsubscribe() {
        AtomicInteger got = new AtomicInteger();
        CombatBus.Listener l = e -> got.incrementAndGet();
        CombatBus.subscribe(l);
        CombatBus.publish(CombatEvent.kill("A", 0, false, "B", 1, false));
        CombatBus.publish(null); // 空事件安全
        assertEquals(1, got.get());
        CombatBus.unsubscribe(l);
        CombatBus.publish(CombatEvent.kill("A", 0, false, "B", 1, true));
        assertEquals(1, got.get());
        assertEquals(0, CombatBus.listenerCount());
    }

    @Test
    void oneBrokenListenerDoesNotBlockOthers() {
        AtomicInteger ok = new AtomicInteger();
        CombatBus.subscribe(e -> {
            throw new IllegalStateException("boom");
        });
        CombatBus.subscribe(e -> ok.incrementAndGet());
        CombatBus.publish(CombatEvent.kill("A", 0, true, "B", 1, false));
        assertEquals(1, ok.get());
    }

    @Test
    void defaultsMatchBfSpirit() {
        BonusTable t = new BonusTable();
        assertEquals(100, t.pointsFor(CombatEventType.KILL));
        assertEquals(25, t.pointsFor(CombatEventType.HEADSHOT));
        assertEquals(150, t.pointsFor(CombatEventType.CAPTURE));
        assertEquals(125, t.killScore(true));
        assertEquals(100, t.killScore(false));
    }

    @Test
    void propertiesOverrideAndMultiplier() {
        Properties p = new Properties();
        p.setProperty("scoring.kill", "150");
        p.setProperty("scoring.headshot", "50");
        p.setProperty("scoring.capture", "0");
        p.setProperty("scoring.multiplier", "2.0");
        BonusTable t = BonusTable.from(p);
        assertEquals(150 * 2, t.pointsFor(CombatEventType.KILL));
        assertEquals(50 * 2, t.pointsFor(CombatEventType.HEADSHOT));
        assertEquals(0, t.pointsFor(CombatEventType.CAPTURE));
        assertEquals((150 + 50) * 2, t.killScore(true));
        assertEquals(150 * 2, t.killScore(false));
        assertEquals(2.0, t.multiplier(), 1e-6);
    }
}
