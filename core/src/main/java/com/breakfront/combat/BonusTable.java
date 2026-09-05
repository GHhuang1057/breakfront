package com.breakfront.combat;

import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

/**
 * 可配置加分表（#47，对齐 656 BonusEngine 的可配置计分事件）。
 *
 * <p>默认值贴合 BF 语境；管理员可在 breakfront-server.properties 中按
 * {@code scoring.&lt;type&gt;=&lt;分&gt;} 覆盖（大小写不敏感），并可整体乘系数
 * {@code scoring.multiplier}（默认 1.0）。
 */
public final class BonusTable {

    public static final Map<CombatEventType, Integer> DEFAULTS = Map.of(
            CombatEventType.KILL, 100,
            CombatEventType.HEADSHOT, 25,
            CombatEventType.ASSIST, 50,
            CombatEventType.CAPTURE, 150,
            CombatEventType.LOSS, 0,
            CombatEventType.SPOT, 10,
            CombatEventType.SUPPRESS, 5,
            CombatEventType.VEHICLE_KILL, 200,
            CombatEventType.REVIVE, 20,
            CombatEventType.MULTIKILL, 50);

    private final Map<CombatEventType, Integer> points;
    private final double multiplier;

    public BonusTable() {
        this(Map.of(), 1.0);
    }

    public BonusTable(Map<CombatEventType, Integer> overrides, double multiplier) {
        EnumMap<CombatEventType, Integer> m = new EnumMap<>(CombatEventType.class);
        DEFAULTS.forEach(m::put);
        if (overrides != null) {
            overrides.forEach((k, v) -> {
                if (v != null) {
                    m.put(k, v);
                }
            });
        }
        this.points = Map.copyOf(m);
        this.multiplier = multiplier;
    }

    /** 从 Properties 构造：读取 scoring.* 键。 */
    public static BonusTable from(Properties p) {
        EnumMap<CombatEventType, Integer> ov = new EnumMap<>(CombatEventType.class);
        double mult = 1.0;
        if (p != null) {
            String mm = p.getProperty("scoring.multiplier");
            if (mm != null) {
                try {
                    mult = Double.parseDouble(mm.trim());
                } catch (NumberFormatException ignored) {
                }
            }
            for (CombatEventType t : CombatEventType.values()) {
                String v = p.getProperty("scoring." + t.propKey());
                if (v != null) {
                    try {
                        ov.put(t, Integer.parseInt(v.trim()));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return new BonusTable(ov, mult);
    }

    /** 单事件加分（向下取整到整数分）。 */
    public int pointsFor(CombatEventType t) {
        int base = points.getOrDefault(t, 0);
        return (int) Math.floor(base * multiplier);
    }

    /** 击杀事件的完整得分：KILL +（爆头则 HEADSHOT）。 */
    public int killScore(boolean headshot) {
        return pointsFor(CombatEventType.KILL)
                + (headshot ? pointsFor(CombatEventType.HEADSHOT) : 0);
    }

    public double multiplier() {
        return multiplier;
    }
}
