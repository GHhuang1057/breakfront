package com.breakfront.client.hud;

import com.breakfront.client.state.ClientMatchState;
import com.breakfront.client.state.ClientMatchState.ZoneView;
import com.breakfront.game.Side;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import org.joml.Matrix4f;

import java.util.List;

/**
 * 据点区域「方块描边 + 半透明地坪」高亮（世界空间渲染）。
 *
 * 取代旧式平滑圆环：沿据点边界逐块画 1×1 方块轮廓线（贴地、对齐方块网格），
 * 圆内铺极淡的半透明地坪，四向清晰可辨 —— 即使在复杂城市地形上也"看得见"。
 * 颜色随占领方：守方蓝 / 攻方黄 / 争夺中白色呼吸闪烁，争夺越激烈 alpha 越高。
 *
 * 由 BreakfrontClient 注册到 WorldRenderEvents；数据每帧从 ClientMatchState 同步。
 * 渲染路径：vanilla immediate 线渲染（官方 lines shader），兼容 Sodium / VulkanMod。
 */
public final class WorldZoneRings {

    private static final int DISC_SEGMENTS = 40;

    private WorldZoneRings() {
    }

    public static void render(WorldRenderContext context) {
        List<ZoneView> zones = ClientMatchState.zones();
        if (zones.isEmpty()) {
            return;
        }
        int phase = ClientMatchState.phaseOrdinal();
        if (phase != 1 && phase != 2) { // COUNTDOWN / BATTLE
            return;
        }

        Matrix4f m = context.positionMatrix();
        long t = System.currentTimeMillis();

        // 1) 地坪（半透明，整体一次过）
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        BufferBuilder fill = Tessellator.getInstance()
                .begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        for (ZoneView zone : zones) {
            int[] c = areaColor(zone, t, false);
            fillDiscInto(fill, m, zone.worldX(), zone.groundY() + 0.05, zone.worldZ(),
                    zone.radius(), c[0], c[1], c[2], 20 + c[3] / 6);
        }
        BufferRenderer.drawWithGlobalProgram(fill.end());

        // 2) 边界方块轮廓线（逐块描边，一次过）
        RenderSystem.setShader(GameRenderer::getRenderTypeLinesProgram);
        BufferBuilder lines = Tessellator.getInstance()
                .begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.LINES);
        for (ZoneView zone : zones) {
            int[] c = areaColor(zone, t, true);
            fillRimBlocksInto(lines, m, zone.worldX(), zone.groundY() + 0.08, zone.worldZ(),
                    zone.radius(), c[0], c[1], c[2], c[3]);
        }
        BufferRenderer.drawWithGlobalProgram(lines.end());
        RenderSystem.disableBlend();
    }

    /** 区域基色：守方蓝 / 攻方黄 / 争夺白→橙呼吸。 */
    private static int[] areaColor(ZoneView zone, long timeMs, boolean bright) {
        Side owner = Side.values()[zone.ownerOrdinal()];
        float pulse = (float) ((timeMs % 800) / 800.0);
        int baseA = bright ? 235 : 120;
        if (owner == Side.ATTACKER) {
            return new int[]{245, 205, 84, baseA};            // 攻方黄
        }
        if (owner == Side.DEFENDER && zone.meter() < 1e-3f) {
            return new int[]{76, 158, 255, bright ? 225 : 120}; // 守方蓝
        }
        // 争夺中：白 ↔ 橙 呼吸
        int r = (int) (255 - 30 * pulse);
        int g = (int) (225 - 130 * pulse);
        int b = (int) (130 - 90 * pulse);
        int a = (int) ((bright ? 190 : 90) + 60 * pulse);
        return new int[]{r, g, b, a};
    }

    /** 沿圆弧铺的 1×1 方块轮廓（每块 4 边 = 8 顶点成 4 段线）。 */
    private static void fillRimBlocksInto(BufferBuilder buf, Matrix4f m,
                                          double cx, double y, double cz,
                                          double radius, int r, int g, int b, int a) {
        if (radius <= 0.3) {
            return;
        }
        int n = (int) Math.min(120, Math.max(12, Math.round(radius * 12.0)));
        float rf = r / 255f;
        float gf = g / 255f;
        float bf = b / 255f;
        float af = a / 255f;
        long prev = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            double ang = Math.PI * 2.0 * i / n;
            int bx = (int) Math.floor(cx + radius * Math.cos(ang));
            int bz = (int) Math.floor(cz + radius * Math.sin(ang));
            long key = ((long) bx << 32) | (bz & 0xFFFFFFFFL);
            if (key == prev) {
                continue;
            }
            prev = key;
            lineEdge(buf, m, bx, y, bz, bx + 1, y, bz, rf, gf, bf, af);
            lineEdge(buf, m, bx + 1, y, bz, bx + 1, y, bz + 1, rf, gf, bf, af);
            lineEdge(buf, m, bx + 1, y, bz + 1, bx, y, bz + 1, rf, gf, bf, af);
            lineEdge(buf, m, bx, y, bz + 1, bx, y, bz, rf, gf, bf, af);
        }
    }

    private static void lineEdge(BufferBuilder buf, Matrix4f m,
                                 double x0, double y0, double z0,
                                 double x1, double y1, double z1,
                                 float r, float g, float b, float a) {
        buf.vertex(m, (float) x0, (float) y0, (float) z0).color(r, g, b, a).normal(0f, 1f, 0f);
        buf.vertex(m, (float) x1, (float) y1, (float) z1).color(r, g, b, a).normal(0f, 1f, 0f);
    }

    /** 半透明圆盘地坪（三角扇铺成 QUADS）。 */
    private static void fillDiscInto(BufferBuilder buf, Matrix4f m,
                                     double cx, double y, double cz,
                                     double radius, int r, int g, int b, int a) {
        if (radius <= 0.3) {
            return;
        }
        float rf = r / 255f;
        float gf = g / 255f;
        float bf = b / 255f;
        float af = Math.min(1f, a / 255f);
        for (int i = 0; i < DISC_SEGMENTS; i++) {
            double a0 = Math.PI * 2.0 * i / DISC_SEGMENTS;
            double a1 = Math.PI * 2.0 * (i + 1) / DISC_SEGMENTS;
            buf.vertex(m, (float) cx, (float) y, (float) cz).color(rf, gf, bf, af);
            buf.vertex(m, (float) (cx + radius * Math.cos(a0)), (float) y,
                    (float) (cz + radius * Math.sin(a0))).color(rf, gf, bf, af);
            buf.vertex(m, (float) (cx + radius * Math.cos(a1)), (float) y,
                    (float) (cz + radius * Math.sin(a1))).color(rf, gf, bf, af);
            buf.vertex(m, (float) cx, (float) y, (float) cz).color(rf, gf, bf, af);
        }
    }
}
