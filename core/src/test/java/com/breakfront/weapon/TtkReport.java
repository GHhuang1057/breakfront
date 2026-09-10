package com.breakfront.weapon;

import java.util.List;
import java.util.Locale;

/**
 * TTK/BTK 对照报告生成器（纯 Java，2026-09-10 v1）。
 *
 * <p>输出当前 {@link WeaponCatalog} 全部武器在 5m / 15m / 30m / 60m 四个距离上的
 * BTK（躯干/爆头）与 TTK，并标注与 BF2042 基准区间的偏差，供调参决策。
 *
 * <p>基准（BF2042 实测，来源：Game8 BTK/TTK 表 + Sym 数据挖掘）：
 * <pre>
 *   顶级 TTK  200-223ms（SMG 近距，如 AC9）
 *   优秀 TTK  240-270ms（AR 全距离，如 VHX-D3 267ms）
 *   中档 TTK  267-300ms
 *   偏低 TTK  356-401ms（长弹匣/远距惩罚态）
 *   AR  近距 4-5 发、中距 5-6 发、远距 6-7 发
 *   SMG 近距 5-6 发；DMR 2-3 发；SR 1-2 发；霰弹近距 1-2 壳
 * </pre>
 */
public final class TtkReport {

    private static final double[] RANGES = {5, 15, 30, 60};

    public static void main(String[] args) {
        List<WeaponSpec> all = WeaponCatalog.all();
        System.out.println("BREAKFRONT 武器 TTK/BTK 对照报告（100HP 体系，TTK 不含反应常数）");
        System.out.println("BF2042 基准：顶级 200-223ms / 优秀 240-270ms / 中档 267-300ms / 偏低 356-401ms");
        System.out.println("=".repeat(118));
        System.out.printf(Locale.ROOT, "%-10s %-6s %5s %5s %5s | %-28s | %-28s%n",
                "weapon", "class", "rpm", "dmg", "head", "BTK 躯干/爆头 (5/15/30/60m)", "TTK ms 躯干/爆头 (5/15/30/60m)");
        System.out.println("-".repeat(118));
        for (WeaponSpec s : all) {
            System.out.printf(Locale.ROOT, "%-10s %-6s %5d %5.0f %5.1f |%s|%s%n",
                    s.id(), s.weaponClass(), s.rpm(), (double) s.damage(), s.headshotMult(),
                    btkCol(s), ttkCol(s));
        }
        System.out.println("=".repeat(118));
        System.out.println("\n【分类分析】");
        for (WeaponClass wc : WeaponClass.values()) {
            analyze(wc, all);
        }
    }

    private static String btkCol(WeaponSpec s) {
        StringBuilder b = new StringBuilder();
        for (double m : RANGES) {
            int t = TtkMath.bulletsToKill(s, m, false);
            int h = TtkMath.bulletsToKill(s, m, true);
            b.append(String.format(Locale.ROOT, " %d/%d", clampInt(t), clampInt(h)));
        }
        return b.toString();
    }

    private static String ttkCol(WeaponSpec s) {
        StringBuilder b = new StringBuilder();
        for (double m : RANGES) {
            double t = TtkMath.ttkMs(s, m, false, 0);
            double h = TtkMath.ttkMs(s, m, true, 0);
            b.append(String.format(Locale.ROOT, " %.0f/%.0f", t, h));
        }
        return b.toString();
    }

    private static int clampInt(int v) {
        return v == Integer.MAX_VALUE ? 99 : v;
    }

    private static void analyze(WeaponClass wc, List<WeaponSpec> all) {
        System.out.println("\n-- " + wc + " --");
        for (WeaponSpec s : all) {
            if (s.weaponClass() != wc) {
                continue;
            }
            double ttkClose = TtkMath.ttkMs(s, 15, false, 0);
            double ttkFar = TtkMath.ttkMs(s, 60, false, 0);
            int btkClose = clampInt(TtkMath.bulletsToKill(s, 15, false));
            String verdict = judge(wc, ttkClose, ttkFar, btkClose);
            System.out.printf(Locale.ROOT, "  %-10s 近距 TTK=%.0fms(%d发)  远距 TTK=%.0fms  → %s%n",
                    s.id(), ttkClose, btkClose, ttkFar, verdict);
        }
    }

    /** 依据武器族给出与 BF2042 基准的偏离判断。 */
    private static String judge(WeaponClass wc, double ttkClose, double ttkFar, int btkClose) {
        String range;
        switch (wc) {
            case SMG -> range = "期望 220-300ms（近距优势）";
            case AR -> range = "期望 260-340ms（全能）";
            case LMG -> range = "期望 300-420ms（持续火力）";
            case DMR -> range = "期望 180-260ms（2-3发）";
            case SR -> range = "期望 0-120ms 或 1-2发";
            case SG -> range = "期望 近距 1-2 壳秒杀";
            case PISTOL -> range = "期望 300-420ms（副武器劣势）";
            default -> range = "";
        }
        if (ttkFar <= 0 && btkClose >= 99) {
            return "⚠ 远距无法击杀（衰减过低）｜" + range;
        }
        return "基准 " + range;
    }
}
