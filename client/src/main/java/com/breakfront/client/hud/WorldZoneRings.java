package com.breakfront.client.hud;

import com.breakfront.client.state.ClientMatchState;
import com.breakfront.client.state.ClientMatchState.ZoneView;
import com.breakfront.game.Side;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.Heightmap;
import org.joml.Matrix4f;

import java.util.List;

/**
 * 据点「地面高亮描边」重做版（2026-09-05 v2，BF2042/GD656 式区域标记）。
 *
 * 上一版（逐块 1×1 方块轮廓 + 服务端单一 groundY）在城市街区几乎不可见：
 * 锯齿离散线太细、固定高度遇高差/建筑即埋地或悬空。本版彻底改画法：
 *
 * 1) 外圈「实色高亮环带」——半径 r-0.45..r+0.4 的连续圆环（QUADS 三角带），
 *    高饱和实色 alpha≈180，约 0.9m 宽，是"描边"的主体，任何距离都清晰。
 * 2) 主环带内外两条「发光外晕」（r-0.9..r-0.5 / r+0.45..r+0.9，alpha≈55）——
 *    GD656 Killicon 多层发光同思路，复杂地形/远距离下先看到晕再看到环。
 * 3) 环带内缘一条 2px 亮线精描边（状态色最高亮度）。
 * 4) 内部极淡地坪（alpha≈30）标出占区范围，不干扰视觉。
 * 5) 中心「目标光柱」——半透明竖柱 + 顶部亮色柱头，楼群/高差中远处可定位。
 *    柱高按中心地表 +5.4m；柱头实色（守蓝/攻黄/争夺白橙呼吸）。
 *
 * 地面锚定：不再信任服务端 groundY（外部图/自建城高差会错位）。
 * 每个环带采样点 + 圆心的高度都由客户端本地高度图实时求值
 * （Heightmap.MOTION_BLOCKING，贴方块顶面 +0.16 防 z-fighting），
 * 区块未加载时回退服务端 groundY。高度缓存约 1s 刷新一次跟踪地形破坏。
 *
 * 渲染路径：vanilla immediate（getPositionColorProgram / getRenderTypeLinesProgram），
 * AFTER_TRANSLUCENT 阶段，兼容 Sodium/Iris。
 */
public final class WorldZoneRings {

    /** 环带/光柱的圆周分段数（够圆、开销小）。 */
    private static final int SEG = 72;
    /** 内部淡地坪分段数。 */
    private static final int DISC_SEG = 40;
    /** 地表以上抬升量（防与方块面 z-fighting）。 */
    private static final float LIFT = 0.16f;
    /** 光柱相对中心地表的高度。 */
    private static final float BEACON_H = 5.4f;
    /** 光柱半宽。 */
    private static final float BEACON_HALF = 0.30f;

    // ---------- 地面高度缓存 ----------
    // zones 指纹（x/z/r 拼接）→ 变了全量重算；否则每 REFIT_MS 刷新一次。
    private static String lastSig = "";
    private static long lastFitMs;
    /** 每 zone：index 0 = 圆心地表 Y，1..SEG = 环带采样点地表 Y（不可用为 NaN）。 */
    private static double[][] ground = new double[0][];
    private static final long REFIT_MS = 1000;

    private WorldZoneRings() {
    }

    // ================= 对外：地面参考高度（屏缘箭头/其它 2D 层共用） =================

    /** 据点中心地表参考 Y（本地高度图，fallback payload groundY）。 */
    public static double anchorY(ZoneView zone) {
        List<ZoneView> zones = ClientMatchState.zones();
        int idx = indexOf(zones, zone);
        if (idx >= 0 && idx < ground.length && !Double.isNaN(ground[idx][0])) {
            return ground[idx][0];
        }
        return zone.groundY();
    }

    private static int indexOf(List<ZoneView> zones, ZoneView zone) {
        for (int i = 0; i < zones.size(); i++) {
            if (zones.get(i) == zone) {
                return i;
            }
        }
        return -1;
    }

    // ================= 主渲染 =================

    public static void render(WorldRenderContext context) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        List<ZoneView> zones = ClientMatchState.zones();
        if (world == null || zones.isEmpty()) {
            return;
        }
        int phase = ClientMatchState.phaseOrdinal();
        if (phase != 1 && phase != 2) { // COUNTDOWN / BATTLE
            return;
        }

        refreshHeights(world, zones);
        Matrix4f m = context.positionMatrix();
        long t = System.currentTimeMillis();

        // ---- 1) 内部淡地坪 + 高亮环带（一次过 QUADS） ----
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        BufferBuilder quads = Tessellator.getInstance()
                .begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        for (int i = 0; i < zones.size(); i++) {
            ZoneView zone = zones.get(i);
            int[] col = areaColor(zone, t, false);
            double yc = centerY(i, zone);
            // 发光外晕（主环带内外各一条低透明宽带，先画作底层）
            fillGlowBandInto(quads, m, zone, i, col[0], col[1], col[2], glowAlpha(t));
            // 内部淡地坪（平面，低 alpha）
            fillDiscInto(quads, m, zone.worldX(), yc + 0.02, zone.worldZ(),
                    zone.radius() - 0.45, col[0], col[1], col[2], 30);
            // 外圈高亮环带（逐采样点贴地）
            fillRingBandInto(quads, m, zone, i, col[0], col[1], col[2], 180);
        }
        BufferRenderer.drawWithGlobalProgram(quads.end());

        // ---- 2) 环带内缘亮线 + 中心光柱 ----
        // 内缘亮线
        RenderSystem.setShader(GameRenderer::getRenderTypeLinesProgram);
        RenderSystem.lineWidth(2.0f);
        BufferBuilder lines = Tessellator.getInstance()
                .begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.LINES);
        for (int i = 0; i < zones.size(); i++) {
            ZoneView zone = zones.get(i);
            int[] col = areaColor(zone, t, true);
            rimBrightLineInto(lines, m, zone, i, col[0], col[1], col[2], 255);
        }
        BufferRenderer.drawWithGlobalProgram(lines.end());
        RenderSystem.lineWidth(1.0f);

        // 中心光柱（QUADS，与环带同样式但单独 alpha）
        BufferBuilder pillars = Tessellator.getInstance()
                .begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        for (int i = 0; i < zones.size(); i++) {
            ZoneView zone = zones.get(i);
            beaconInto(pillars, m, zone, i, t);
        }
        BufferRenderer.drawWithGlobalProgram(pillars.end());
        RenderSystem.disableBlend();
    }

    // ================= 高度计算与缓存 =================

    private static void refreshHeights(ClientWorld world, List<ZoneView> zones) {
        long now = System.currentTimeMillis();
        StringBuilder sig = new StringBuilder();
        for (ZoneView z : zones) {
            sig.append((long) z.worldX() * 4).append(',').append((long) z.worldZ() * 4)
                    .append(',').append((long) (z.radius() * 4)).append(';');
        }
        String s = sig.toString();
        boolean needFit = !s.equals(lastSig) || (now - lastFitMs > REFIT_MS);
        if (!needFit && ground.length == zones.size()) {
            return;
        }
        lastSig = s;
        lastFitMs = now;
        ground = new double[zones.size()][];
        for (int i = 0; i < zones.size(); i++) {
            ZoneView z = zones.get(i);
            double[] g = new double[SEG + 1];
            g[0] = groundYAt(world, z.worldX(), z.worldZ());
            for (int k = 0; k < SEG; k++) {
                double ang = Math.PI * 2.0 * k / SEG;
                double sx = z.worldX() + (z.radius() - 0.45) * Math.cos(ang);
                double sz = z.worldZ() + (z.radius() - 0.45) * Math.sin(ang);
                g[k + 1] = groundYAt(world, sx, sz);
            }
            ground[i] = g;
        }
    }

    /** 客户端本地地表 Y（最高实体阻挡方块顶 +1）；区块未加载返回 NaN（调用方兜底）。 */
    private static double groundYAt(ClientWorld world, double x, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        if (!world.getChunkManager().isChunkLoaded(bx >> 4, bz >> 4)) {
            return Double.NaN;
        }
        int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING, bx, bz);
        return top + 1;
    }

    private static double centerY(int idx, ZoneView zone) {
        if (idx >= 0 && idx < ground.length && !Double.isNaN(ground[idx][0])) {
            return ground[idx][0];
        }
        return zone.groundY();
    }

    // ================= 颜色 =================

    /** 区域基色：守方蓝 / 攻方黄 / 争夺白→橙呼吸。bright=true 给描边线用。 */
    private static int[] areaColor(ZoneView zone, long timeMs, boolean bright) {
        Side owner = Side.values()[zone.ownerOrdinal()];
        float pulse = (float) ((timeMs % 800) / 800.0);
        if (owner == Side.ATTACKER) {
            return new int[]{245, 205, 84, bright ? 255 : 210};      // 攻方黄
        }
        if (owner == Side.DEFENDER && zone.meter() < 1e-3f) {
            return new int[]{76, 158, 255, bright ? 255 : 200};      // 守方蓝
        }
        // 争夺中：白 ↔ 橙 呼吸
        int r = (int) (255 - 30 * pulse);
        int g = (int) (225 - 130 * pulse);
        int b = (int) (130 - 90 * pulse);
        int a = (int) ((bright ? 255 : 150) + 70 * pulse);
        return new int[]{r, g, Math.max(0, b), Math.min(255, a)};
    }

    // ================= 几何 =================

    /** 发光外晕：主环带内外各一条低透明宽带（同逐采样贴地画法）。 */
    private static void fillGlowBandInto(BufferBuilder buf, Matrix4f m,
                                         ZoneView zone, int idx,
                                         int r, int g, int b, int a) {
        double radius = zone.radius();
        fillBandSegment(buf, m, zone, idx, radius - 0.95, radius - 0.50, r, g, b, a);
        fillBandSegment(buf, m, zone, idx, radius + 0.42, radius + 0.95, r, g, b, a);
    }

    /** 外晕呼吸强度（与主环带错相，营造柔和脉动）。 */
    private static int glowAlpha(long timeMs) {
        float pulse = (float) ((timeMs % 1400) / 1400.0);
        return 42 + (int) (20 * Math.sin(pulse * Math.PI * 2.0));
    }

    /** 高亮环带：r-0.45 → r+0.4 圆环三角带，逐采样点贴地。 */
    private static void fillRingBandInto(BufferBuilder buf, Matrix4f m,
                                         ZoneView zone, int idx,
                                         int r, int g, int b, int a) {
        double radius = zone.radius();
        fillBandSegment(buf, m, zone, idx, radius - 0.45, radius + 0.40, r, g, b, a);
    }

    /** 任意半径区间的一段贴地环带（QUADS，四顶点/段，逐采样点高度）。 */
    private static void fillBandSegment(BufferBuilder buf, Matrix4f m,
                                        ZoneView zone, int idx,
                                        double rIn, double rOut,
                                        int r, int g, int b, int a) {
        double cx = zone.worldX();
        double cz = zone.worldZ();
        double[] gs = (idx >= 0 && idx < ground.length) ? ground[idx] : null;
        float rf = r / 255f;
        float gf = g / 255f;
        float bf = b / 255f;
        float af = Math.min(1f, a / 255f);
        for (int k = 0; k < SEG; k++) {
            double a0 = Math.PI * 2.0 * k / SEG;
            double a1 = Math.PI * 2.0 * (k + 1) / SEG;
            double y0 = (gs != null && !Double.isNaN(gs[k + 1])) ? gs[k + 1] : centerY(idx, zone);
            double y1 = (gs != null && !Double.isNaN(gs[((k + 1) % SEG) + 1]))
                    ? gs[((k + 1) % SEG) + 1] : y0;
            float fy0 = (float) (y0 + LIFT);
            float fy1 = (float) (y1 + LIFT);
            double c0 = Math.cos(a0), s0 = Math.sin(a0);
            double c1 = Math.cos(a1), s1 = Math.sin(a1);
            buf.vertex(m, (float) (cx + rIn * c0), fy0, (float) (cz + rIn * s0)).color(rf, gf, bf, af);
            buf.vertex(m, (float) (cx + rOut * c0), fy0, (float) (cz + rOut * s0)).color(rf, gf, bf, af);
            buf.vertex(m, (float) (cx + rOut * c1), fy1, (float) (cz + rOut * s1)).color(rf, gf, bf, af);
            buf.vertex(m, (float) (cx + rIn * c1), fy1, (float) (cz + rIn * s1)).color(rf, gf, bf, af);
        }
    }

    /** 环带内缘高亮线（r-0.45 处连续折线，逐点贴地）。 */
    private static void rimBrightLineInto(BufferBuilder buf, Matrix4f m,
                                          ZoneView zone, int idx,
                                          int r, int g, int b, int a) {
        double cx = zone.worldX();
        double cz = zone.worldZ();
        double rr = zone.radius() - 0.45;
        double[] gs = (idx >= 0 && idx < ground.length) ? ground[idx] : null;
        float rf = r / 255f;
        float gf = g / 255f;
        float bf = b / 255f;
        float af = Math.min(1f, a / 255f);
        for (int k = 0; k <= SEG; k++) {
            int kk = k % SEG;
            double ang = Math.PI * 2.0 * kk / SEG;
            double y = (gs != null && !Double.isNaN(gs[kk + 1])) ? gs[kk + 1] : centerY(idx, zone);
            double ax = cx + rr * Math.cos(ang);
            double az = cz + rr * Math.sin(ang);
            double ang2 = Math.PI * 2.0 * ((kk + 1) % SEG) / SEG;
            double y2 = (gs != null && !Double.isNaN(gs[((kk + 1) % SEG) + 1]))
                    ? gs[((kk + 1) % SEG) + 1] : y;
            double bx2 = cx + rr * Math.cos(ang2);
            double bz2 = cz + rr * Math.sin(ang2);
            buf.vertex(m, (float) ax, (float) (y + LIFT), (float) az).color(rf, gf, bf, af)
                    .normal(0f, 1f, 0f);
            buf.vertex(m, (float) bx2, (float) (y2 + LIFT), (float) bz2).color(rf, gf, bf, af)
                    .normal(0f, 1f, 0f);
        }
    }

    /** 半透明圆盘（内部淡色地坪）。 */
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
        for (int i = 0; i < DISC_SEG; i++) {
            double a0 = Math.PI * 2.0 * i / DISC_SEG;
            double a1 = Math.PI * 2.0 * (i + 1) / DISC_SEG;
            buf.vertex(m, (float) cx, (float) y, (float) cz).color(rf, gf, bf, af);
            buf.vertex(m, (float) (cx + radius * Math.cos(a0)), (float) y,
                    (float) (cz + radius * Math.sin(a0))).color(rf, gf, bf, af);
            buf.vertex(m, (float) (cx + radius * Math.cos(a1)), (float) y,
                    (float) (cz + radius * Math.sin(a1))).color(rf, gf, bf, af);
            buf.vertex(m, (float) cx, (float) y, (float) cz).color(rf, gf, bf, af);
        }
    }

    /** 中心目标光柱：半透明柱身 + 顶部高亮柱头（两次方盒）。 */
    private static void beaconInto(BufferBuilder buf, Matrix4f m,
                                   ZoneView zone, int idx, long nowMs) {
        Side owner = Side.values()[zone.ownerOrdinal()];
        boolean contested = owner == Side.DEFENDER && zone.meter() > 1e-3f;
        int[] col = areaColor(zone, nowMs, true);
        double cx = zone.worldX();
        double cz = zone.worldZ();
        double y0 = centerY(idx, zone) + 0.15;
        double yTop = y0 + BEACON_H;
        double h = BEACON_HALF;
        double pad = 0.16;
        float rf = col[0] / 255f;
        float gf = col[1] / 255f;
        float bf = col[2] / 255f;
        // 柱身（细、半透明，顶盖封口）
        quad(buf, m, cx - h, cx + h, cz - h, cz + h, y0, yTop,
                rf, gf, bf, contested ? 0.50f : 0.38f);
        // 柱头（粗一圈、高亮实色，仅柱顶再高 1.0m）
        quad(buf, m, cx - h - pad, cx + h + pad, cz - h - pad, cz + h + pad,
                yTop, yTop + 1.0, rf, gf, bf, 0.92f);
    }

    /** 单层方盒四侧壁（柱头用）：给定 X/Z 范围与 Y 范围。 */
    private static void quad(BufferBuilder buf, Matrix4f m,
                             double x0, double x1, double z0, double z1,
                             double y0, double y1,
                             float r, float g, float b, float a) {
        // +x
        buf.vertex(m, (float) x1, (float) y0, (float) z0).color(r, g, b, a);
        buf.vertex(m, (float) x1, (float) y0, (float) z1).color(r, g, b, a);
        buf.vertex(m, (float) x1, (float) y1, (float) z1).color(r, g, b, a);
        buf.vertex(m, (float) x1, (float) y1, (float) z0).color(r, g, b, a);
        // -x
        buf.vertex(m, (float) x0, (float) y0, (float) z1).color(r, g, b, a);
        buf.vertex(m, (float) x0, (float) y0, (float) z0).color(r, g, b, a);
        buf.vertex(m, (float) x0, (float) y1, (float) z0).color(r, g, b, a);
        buf.vertex(m, (float) x0, (float) y1, (float) z1).color(r, g, b, a);
        // +z
        buf.vertex(m, (float) x1, (float) y0, (float) z1).color(r, g, b, a);
        buf.vertex(m, (float) x0, (float) y0, (float) z1).color(r, g, b, a);
        buf.vertex(m, (float) x0, (float) y1, (float) z1).color(r, g, b, a);
        buf.vertex(m, (float) x1, (float) y1, (float) z1).color(r, g, b, a);
        // -z
        buf.vertex(m, (float) x0, (float) y0, (float) z0).color(r, g, b, a);
        buf.vertex(m, (float) x1, (float) y0, (float) z0).color(r, g, b, a);
        buf.vertex(m, (float) x1, (float) y1, (float) z0).color(r, g, b, a);
        buf.vertex(m, (float) x0, (float) y1, (float) z0).color(r, g, b, a);
        // 顶
        buf.vertex(m, (float) x0, (float) y1, (float) z0).color(r, g, b, a);
        buf.vertex(m, (float) x1, (float) y1, (float) z0).color(r, g, b, a);
        buf.vertex(m, (float) x1, (float) y1, (float) z1).color(r, g, b, a);
        buf.vertex(m, (float) x0, (float) y1, (float) z1).color(r, g, b, a);
    }
}
