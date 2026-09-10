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
 * 据点「方块领地」地面标线重做版（2026-09-05 v3，方形语义）。
 *
 * 依据服务端 ZoneAnchor v2 的方形判定（|dx|≤r 且 |dz|≤r），视觉侧同样以外接正方形
 * 呈现，取代 v2 的圆环/圆坪（用户反馈：悬空、非方形、看不清边界）。
 *
 * 画法（GD656 多层发光思路，正方形四边）：
 * 1) 内部淡色方坪（alpha≈26，整片低干扰地标出占领范围，单平面贴中心高）
 * 2) 边界四边「高亮发光带」——宽约 0.75m 且绝大部分压在边界外侧（boundary..boundary+0.75），
 *    沿边逐段贴地（每 ~1.5m 一采样），是"方块边缘亮光"的主体；
 * 3) 边带外侧再压一条低透明「外晕」（+0.75..+1.25，alpha≈50，呼吸）拉出发光层次；
 * 4) 边带内缘 2px 亮线精描（在 boundary-0.08 处，最高亮度）；
 * 5) 四角「角柱」——垂直细柱 + 顶块，方框四角在空中也清晰可辨（楼群/坡地中定位）；
 * 6) 中心「目标光柱」抬高至 7.5m（半透柱身 + 高亮柱头）——地标位于领地光亮中心。
 *
 * 地面锚定：全部走客户端本地高度图（Heightmap.MOTION_BLOCKING 顶 +1，贴块顶 +0.16
 * 防 z-fighting），每 ~1s 刷新一次跟踪地形变化；区块未加载回退服务端 groundY。
 *
 * 渲染路径：vanilla immediate（getPositionColorProgram / LINES），AFTER_TRANSLUCENT
 * 阶段，兼容 Sodium/Iris。
 */
public final class WorldZoneRings {

    /** 地表以上抬升量（防与方块面 z-fighting）。 */
    private static final float LIFT = 0.16f;
    /** 中心目标光柱相对中心地表的高度。 */
    private static final float BEACON_H = 7.5f;
    /** 光柱半宽。 */
    private static final float BEACON_HALF = 0.28f;
    /** 角柱高度。 */
    private static final float CORNER_H = 2.6f;
    /** 领地边界「体积墙」高度（Area Selector 式半透明墙，站在里面也看得到范围）。 */
    private static final float WALL_H = 2.4f;
    /** 沿边每段的期望地面采样步长（米）。 */
    private static final double STEP = 1.5;
    /** 单边采样点数上限（4 边合计上限 160，内存/开销可控）。 */
    private static final int MAX_PER_EDGE = 40;

    // ---------- 地面高度缓存 ----------
    // 每 zone 一条：index0=中心地表 Y；随后按 上→右→下→左 四边各 perEdge 点（顺时针）。
    private static String lastSig = "";
    private static long lastFitMs;
    private static double[][] ground = new double[0][];
    private static int[] perEdge = new int[0];
    /**
     * 每个据点「是否有可信地面」。false = 本地未加载且服务端也没给出有效高度
     * （坐标落在未生成区块/虚空）→ 该据点整条不渲染。
     *
     * <p>⚠️ 2026-09-10：以前这里没有这个判据，`centerY` 会退化成服务端旧版下发的
     * `bottomY+3`（≈-61）假高度 —— 于是据点方坪/边带/角柱/光柱全被画到基岩层以下，
     * 玩家在地面只看到错位残影或完全看不到。现在宁可不画，也不画错位置。
     */
    private static boolean[] valid = new boolean[0];
    private static final long REFIT_MS = 1000;

    private WorldZoneRings() {
    }

    // ================= 对外：地面参考高度（屏缘箭头/其它 2D 层共用） =================

    /** 据点中心地表参考 Y（本地高度图，fallback 服务端 groundY）；无有效地面返回 NaN。 */
    public static double anchorY(ZoneView zone) {
        List<ZoneView> zones = ClientMatchState.zones();
        int idx = indexOf(zones, zone);
        if (idx >= 0 && idx < ground.length && !Double.isNaN(ground[idx][0])) {
            return ground[idx][0];
        }
        return zone.groundY();
    }

    /** 该据点是否有可信地面（渲染前统一判据）。 */
    public static boolean hasGround(ZoneView zone) {
        int idx = indexOf(ClientMatchState.zones(), zone);
        if (idx >= 0 && idx < valid.length) {
            return valid[idx];
        }
        return !Double.isNaN(zone.groundY());
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

        // ---- 1) 内部淡色方坪 + 高亮边带 + 外晕（一次 QUADS） ----
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        BufferBuilder quads = Tessellator.getInstance()
                .begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        for (int i = 0; i < zones.size(); i++) {
            if (!isValidIdx(i)) {
                continue;                       // 无有效地面 → 不画（避免画在基岩层/错位）
            }
            ZoneView zone = zones.get(i);
            int[] col = areaColor(zone, t, false);
            double yc = centerY(i, zone);
            double[] b = bounds(zone);
            // 内部淡色方坪（单平面贴中心高，低干扰）
            fillPlateInto(quads, m, b, yc + 0.02, col, 38);
            // 外晕（边外 +0.75..+1.25）
            fillEdgeBandInto(quads, m, b, i, 0.75, 1.25, col[0], col[1], col[2], glowAlpha(t));
            // 高亮边带（boundary..+0.75 大部分在边外，贴地）
            fillEdgeBandInto(quads, m, b, i, 0.0, 0.75, col[0], col[1], col[2], 210);
            // 领地边界体积墙（半透明竖墙，站在领地内也可看清范围）
            wallSheetInto(quads, m, b, i, col, wallAlpha(zone, t));
        }
        BufferRenderer.drawWithGlobalProgram(quads.end());

        // ---- 2) 边带内缘亮线 + 顶部轮廓线（boundary-0.08 / 墙顶高度处） ----
        RenderSystem.setShader(GameRenderer::getRenderTypeLinesProgram);
        RenderSystem.lineWidth(2.0f);
        BufferBuilder lines = Tessellator.getInstance()
                .begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.LINES);
        for (int i = 0; i < zones.size(); i++) {
            if (!isValidIdx(i)) {
                continue;
            }
            ZoneView zone = zones.get(i);
            int[] col = areaColor(zone, t, true);
            rimLineInto(lines, m, bounds(zone), i, col[0], col[1], col[2], 255);
            topLineInto(lines, m, bounds(zone), i, centerY(i, zone) + WALL_H,
                    col[0], col[1], col[2], 200);
        }
        BufferRenderer.drawWithGlobalProgram(lines.end());
        RenderSystem.lineWidth(1.0f);

        // ---- 3) 四角柱 + 中心光柱（QUADS） ----
        BufferBuilder pillars = Tessellator.getInstance()
                .begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        for (int i = 0; i < zones.size(); i++) {
            if (!isValidIdx(i)) {
                continue;
            }
            ZoneView zone = zones.get(i);
            int[] col = areaColor(zone, t, true);
            double[] b = bounds(zone);
            double y0 = centerY(i, zone);
            cornersInto(pillars, m, b, y0, col);
            beaconInto(pillars, m, zone, i, t);
        }
        BufferRenderer.drawWithGlobalProgram(pillars.end());
        RenderSystem.disableBlend();
    }

    // ================= 几何工具 =================

    /** 外接方界 {x0,z0,x1,z1}（radius 即半边长）。 */
    private static double[] bounds(ZoneView zone) {
        double r = zone.radius();
        return new double[]{zone.worldX() - r, zone.worldZ() - r,
                zone.worldX() + r, zone.worldZ() + r};
    }

    /** 单边采样点数。 */
    private static int perEdgeOf(ZoneView zone) {
        int n = (int) Math.ceil(2.0 * Math.max(1, zone.radius()) / STEP);
        return Math.max(4, Math.min(MAX_PER_EDGE, n));
    }

    /** 内部淡色方坪（半透明平面，压地形起伏近似单面）。 */
    private static void fillPlateInto(BufferBuilder buf, Matrix4f m, double[] b,
                                      double y, int[] col, int a) {
        float rf = col[0] / 255f;
        float gf = col[1] / 255f;
        float bf = col[2] / 255f;
        float af = Math.min(1f, a / 255f);
        float fy = (float) y;
        buf.vertex(m, (float) b[0], fy, (float) b[1]).color(rf, gf, bf, af);
        buf.vertex(m, (float) b[0], fy, (float) b[3]).color(rf, gf, bf, af);
        buf.vertex(m, (float) b[2], fy, (float) b[3]).color(rf, gf, bf, af);
        buf.vertex(m, (float) b[2], fy, (float) b[1]).color(rf, gf, bf, af);
    }

    /**
     * 沿正方形四边画一段「贴地发光带」：外扩量 offIn..offOut（0=正好压边界线）。
     * 采样点：四边顺时针各 perEdge 段；缓存索引 i=1+edge*perEdge+k。
     */
    private static void fillEdgeBandInto(BufferBuilder buf, Matrix4f m, double[] b,
                                         int idx, double offIn, double offOut,
                                         int r, int g, int bl, int a) {
        double[] gs = (idx >= 0 && idx < ground.length) ? ground[idx] : null;
        int pe = (idx >= 0 && idx < perEdge.length) ? perEdge[idx] : 16;
        float rf = r / 255f;
        float gf = g / 255f;
        float bf = bl / 255f;
        float af = Math.min(1f, a / 255f);
        // 每边起点终点（顺时针：上边 z=z0 从左到右；右边 x=x1 从上到下；下边 z=z1 从右到左；左边 x=x0 从下到上）
        double[][][] edges = {
                {{b[0], b[1]}, {b[2], b[1]}},
                {{b[2], b[1]}, {b[2], b[3]}},
                {{b[2], b[3]}, {b[0], b[3]}},
                {{b[0], b[3]}, {b[0], b[1]}},
        };
        for (int e = 0; e < 4; e++) {
            double xa = edges[e][0][0], za = edges[e][0][1];
            double xb = edges[e][1][0], zb = edges[e][1][1];
            double len = Math.hypot(xb - xa, zb - za);
            // 外向法线（右手边朝外：顺时针前进时右侧即外部）
            double nx = (zb - za) / len;
            double nz = -(xb - xa) / len;
            int segs = pe;
            for (int k = 0; k < segs; k++) {
                double t0 = (double) k / segs;
                double t1 = (double) (k + 1) / segs;
                double p0x = xa + (xb - xa) * t0;
                double p0z = za + (zb - za) * t0;
                double p1x = xa + (xb - xa) * t1;
                double p1z = za + (zb - za) * t1;
                double y0 = edgeY(gs, pe, e, k);
                double y1 = edgeY(gs, pe, e, Math.min(k + 1, segs - 1));
                if (Double.isNaN(y0)) y0 = centerY(idx, gs);
                if (Double.isNaN(y1)) y1 = centerY(idx, gs);
                float fy0 = (float) (y0 + LIFT);
                float fy1 = (float) (y1 + LIFT);
                buf.vertex(m, (float) (p0x + nx * offIn), fy0, (float) (p0z + nz * offIn)).color(rf, gf, bf, af);
                buf.vertex(m, (float) (p0x + nx * offOut), fy0, (float) (p0z + nz * offOut)).color(rf, gf, bf, af);
                buf.vertex(m, (float) (p1x + nx * offOut), fy1, (float) (p1z + nz * offOut)).color(rf, gf, bf, af);
                buf.vertex(m, (float) (p1x + nx * offIn), fy1, (float) (p1z + nz * offIn)).color(rf, gf, bf, af);
            }
        }
    }

    /** 缓存内某边某采样点的地表 Y；无缓存/越界返回 NaN。 */
    private static double edgeY(double[] gs, int pe, int edge, int k) {
        if (gs == null || pe <= 0 || edge < 0 || edge >= 4 || k < 0 || k >= pe) {
            return Double.NaN;
        }
        return gs[1 + edge * pe + k];
    }

    /** 边带内缘亮线（boundary-0.08，顺时针四边）。 */
    private static void rimLineInto(BufferBuilder buf, Matrix4f m, double[] b,
                                    int idx, int r, int g, int bl, int a) {
        double[] gs = (idx >= 0 && idx < ground.length) ? ground[idx] : null;
        int pe = (idx >= 0 && idx < perEdge.length) ? perEdge[idx] : 16;
        float rf = r / 255f;
        float gf = g / 255f;
        float bf = bl / 255f;
        float af = Math.min(1f, a / 255f);
        double off = -0.08;
        double[][][] edges = {
                {{b[0], b[1]}, {b[2], b[1]}},
                {{b[2], b[1]}, {b[2], b[3]}},
                {{b[2], b[3]}, {b[0], b[3]}},
                {{b[0], b[3]}, {b[0], b[1]}},
        };
        for (int e = 0; e < 4; e++) {
            double xa = edges[e][0][0], za = edges[e][0][1];
            double xb = edges[e][1][0], zb = edges[e][1][1];
            double len = Math.hypot(xb - xa, zb - za);
            double nx = (zb - za) / len;
            double nz = -(xb - xa) / len;
            int segs = pe;
            for (int k = 0; k < segs; k++) {
                double t0 = (double) k / segs;
                double t1 = (double) (k + 1) / segs;
                double p0x = xa + (xb - xa) * t0;
                double p0z = za + (zb - za) * t0;
                double p1x = xa + (xb - xa) * t1;
                double p1z = za + (zb - za) * t1;
                double y0 = edgeY(gs, pe, e, k);
                double y1 = edgeY(gs, pe, e, Math.min(k + 1, segs - 1));
                if (Double.isNaN(y0)) y0 = centerY(idx, gs);
                if (Double.isNaN(y1)) y1 = centerY(idx, gs);
                buf.vertex(m, (float) (p0x + nx * off), (float) (y0 + LIFT), (float) (p0z + nz * off))
                        .color(rf, gf, bf, af).normal(0f, 1f, 0f);
                buf.vertex(m, (float) (p1x + nx * off), (float) (y1 + LIFT), (float) (p1z + nz * off))
                        .color(rf, gf, bf, af).normal(0f, 1f, 0f);
            }
        }
    }

    /** 领地边界「半透明体积墙」：沿四边在边界线位置立一竖墙（0..WALL_H），
     *  站在领地内部也能清楚地看到占领范围边界（Area Selector V1 同款可见性思路）。 */
    private static void wallSheetInto(BufferBuilder buf, Matrix4f m, double[] b,
                                      int idx, int[] col, int a) {
        double[] gs = (idx >= 0 && idx < ground.length) ? ground[idx] : null;
        int pe = (idx >= 0 && idx < perEdge.length) ? perEdge[idx] : 16;
        float rf = col[0] / 255f;
        float gf = col[1] / 255f;
        float bf = col[2] / 255f;
        float af = Math.min(1f, a / 255f);
        double[][][] edges = {
                {{b[0], b[1]}, {b[2], b[1]}},
                {{b[2], b[1]}, {b[2], b[3]}},
                {{b[2], b[3]}, {b[0], b[3]}},
                {{b[0], b[3]}, {b[0], b[1]}},
        };
        for (int e = 0; e < 4; e++) {
            double xa = edges[e][0][0], za = edges[e][0][1];
            double xb = edges[e][1][0], zb = edges[e][1][1];
            double len = Math.hypot(xb - xa, zb - za);
            if (len < 1e-6) {
                continue;
            }
            int segs = pe;
            for (int k = 0; k < segs; k++) {
                double t0 = (double) k / segs;
                double t1 = (double) (k + 1) / segs;
                double p0x = xa + (xb - xa) * t0;
                double p0z = za + (zb - za) * t0;
                double p1x = xa + (xb - xa) * t1;
                double p1z = za + (zb - za) * t1;
                double y0 = edgeY(gs, pe, e, k);
                double y1 = edgeY(gs, pe, e, Math.min(k + 1, segs - 1));
                if (Double.isNaN(y0)) y0 = centerY(idx, gs);
                if (Double.isNaN(y1)) y1 = centerY(idx, gs);
                float fy0 = (float) (y0 + LIFT);
                float fy1 = (float) (y1 + LIFT);
                float fyT = (float) (Math.max(y0, y1) + LIFT + WALL_H);
                // 两个三角形组成一个竖直墙面段
                buf.vertex(m, (float) p0x, fy0, (float) p0z).color(rf, gf, bf, af);
                buf.vertex(m, (float) p0x, fyT, (float) p0z).color(rf, gf, bf, af);
                buf.vertex(m, (float) p1x, fyT, (float) p1z).color(rf, gf, bf, af);
                buf.vertex(m, (float) p1x, fy1, (float) p1z).color(rf, gf, bf, af);
            }
        }
    }

    /** 顶部轮廓线：在固定高度 yTop 沿四边画一圈（体积墙顶界，远处也可见盒体）。 */
    private static void topLineInto(BufferBuilder buf, Matrix4f m, double[] b,
                                    int idx, double yTop, int r, int g, int bl, int a) {
        double[][][] edges = {
                {{b[0], b[1]}, {b[2], b[1]}},
                {{b[2], b[1]}, {b[2], b[3]}},
                {{b[2], b[3]}, {b[0], b[3]}},
                {{b[0], b[3]}, {b[0], b[1]}},
        };
        float rf = r / 255f;
        float gf = g / 255f;
        float bf = bl / 255f;
        float af = Math.min(1f, a / 255f);
        float fy = (float) yTop;
        for (double[][] ed : edges) {
            buf.vertex(m, (float) ed[0][0], fy, (float) ed[0][1]).color(rf, gf, bf, af)
                    .normal(0f, 1f, 0f);
            buf.vertex(m, (float) ed[1][0], fy, (float) ed[1][1]).color(rf, gf, bf, af)
                    .normal(0f, 1f, 0f);
        }
    }

    /** 四角竖直角柱（y0..y0+CORNER_H，顶加粗亮块）。 */
    private static void cornersInto(BufferBuilder buf, Matrix4f m, double[] b,
                                    double y0, int[] col) {
        float rf = col[0] / 255f;
        float gf = col[1] / 255f;
        float bf = col[2] / 255f;
        double h = 0.16;
        double top = y0 + CORNER_H;
        double pad = 0.10;
        double[][] corners = {
                {b[0], b[1]}, {b[2], b[1]}, {b[2], b[3]}, {b[0], b[3]}
        };
        for (double[] c : corners) {
            double cx = c[0], cz = c[1];
            quad(buf, m, cx - h, cx + h, cz - h, cz + h, y0, top, rf, gf, bf, 0.42f);
            quad(buf, m, cx - h - pad, cx + h + pad, cz - h - pad, cz + h + pad,
                    top, top + 0.7, rf, gf, bf, 0.95f);
        }
    }

    /** 中心目标光柱：半透明柱身 + 顶部高亮柱头（地标位于领地光亮中心）。 */
    private static void beaconInto(BufferBuilder buf, Matrix4f m,
                                   ZoneView zone, int idx, long nowMs) {
        int[] col = areaColor(zone, nowMs, true);
        double cx = zone.worldX();
        double cz = zone.worldZ();
        double y0 = centerY(idx, zone) + 0.15;
        double yTop = y0 + BEACON_H;
        double h = BEACON_HALF;
        double pad = 0.18;
        float rf = col[0] / 255f;
        float gf = col[1] / 255f;
        float bf = col[2] / 255f;
        boolean contested = Side.values()[zone.ownerOrdinal()] == Side.DEFENDER
                && zone.meter() > 1e-3f;
        quad(buf, m, cx - h, cx + h, cz - h, cz + h, y0, yTop,
                rf, gf, bf, contested ? 0.50f : 0.36f);
        quad(buf, m, cx - h - pad, cx + h + pad, cz - h - pad, cz + h + pad,
                yTop, yTop + 1.1, rf, gf, bf, 0.95f);
    }

    /** 单层方盒四侧壁+顶（柱体用）。 */
    private static void quad(BufferBuilder buf, Matrix4f m,
                             double x0, double x1, double z0, double z1,
                             double y0, double y1,
                             float r, float g, float b, float a) {
        buf.vertex(m, (float) x1, (float) y0, (float) z0).color(r, g, b, a);
        buf.vertex(m, (float) x1, (float) y0, (float) z1).color(r, g, b, a);
        buf.vertex(m, (float) x1, (float) y1, (float) z1).color(r, g, b, a);
        buf.vertex(m, (float) x1, (float) y1, (float) z0).color(r, g, b, a);
        buf.vertex(m, (float) x0, (float) y0, (float) z1).color(r, g, b, a);
        buf.vertex(m, (float) x0, (float) y0, (float) z0).color(r, g, b, a);
        buf.vertex(m, (float) x0, (float) y1, (float) z0).color(r, g, b, a);
        buf.vertex(m, (float) x0, (float) y1, (float) z1).color(r, g, b, a);
        buf.vertex(m, (float) x1, (float) y0, (float) z1).color(r, g, b, a);
        buf.vertex(m, (float) x0, (float) y0, (float) z1).color(r, g, b, a);
        buf.vertex(m, (float) x0, (float) y1, (float) z1).color(r, g, b, a);
        buf.vertex(m, (float) x1, (float) y1, (float) z1).color(r, g, b, a);
        buf.vertex(m, (float) x0, (float) y0, (float) z0).color(r, g, b, a);
        buf.vertex(m, (float) x1, (float) y0, (float) z0).color(r, g, b, a);
        buf.vertex(m, (float) x1, (float) y1, (float) z0).color(r, g, b, a);
        buf.vertex(m, (float) x0, (float) y1, (float) z0).color(r, g, b, a);
        buf.vertex(m, (float) x0, (float) y1, (float) z0).color(r, g, b, a);
        buf.vertex(m, (float) x1, (float) y1, (float) z0).color(r, g, b, a);
        buf.vertex(m, (float) x1, (float) y1, (float) z1).color(r, g, b, a);
        buf.vertex(m, (float) x0, (float) y1, (float) z1).color(r, g, b, a);
    }

    // ================= 高度计算与缓存 =================

    private static void refreshHeights(ClientWorld world, List<ZoneView> zones) {
        long now = System.currentTimeMillis();
        StringBuilder sig = new StringBuilder();
        for (ZoneView z : zones) {
            double r = z.radius();
            sig.append((long) (z.worldX() * 4)).append(',')
                    .append((long) (z.worldZ() * 4)).append(',')
                    .append((long) (r * 4)).append(';');
        }
        String s = sig.toString();
        boolean needFit = !s.equals(lastSig) || (now - lastFitMs > REFIT_MS);
        if (!needFit && ground.length == zones.size()) {
            return;
        }
        lastSig = s;
        lastFitMs = now;
        ground = new double[zones.size()][];
        perEdge = new int[zones.size()];
        valid = new boolean[zones.size()];
        for (int i = 0; i < zones.size(); i++) {
            ZoneView z = zones.get(i);
            int pe = perEdgeOf(z);
            perEdge[i] = pe;
            double[] b = bounds(z);
            // 中心 + 四边各 pe 个点（顺时针）
            double[] g = new double[1 + 4 * pe];
            g[0] = centerGround(world, z);
            // 本地未加载（高度图 NaN）→ 用服务端下发的 groundY 兜底，保证整圈贴同一层；
            // 两边都没有才判定为「无地面」，由 valid[] 统一跳过渲染。
            if (Double.isNaN(g[0]) && !Double.isNaN(z.groundY())) {
                g[0] = z.groundY();
            }
            double[][][] edges = {
                    {{b[0], b[1]}, {b[2], b[1]}},
                    {{b[2], b[1]}, {b[2], b[3]}},
                    {{b[2], b[3]}, {b[0], b[3]}},
                    {{b[0], b[3]}, {b[0], b[1]}},
            };
            for (int e = 0; e < 4; e++) {
                double xa = edges[e][0][0], za = edges[e][0][1];
                double xb = edges[e][1][0], zb = edges[e][1][1];
                for (int k = 0; k < pe; k++) {
                    double t = (double) k / pe;
                    double sx = xa + (xb - xa) * t;
                    double sz = za + (zb - za) * t;
                    g[1 + e * pe + k] = groundYAt(world, sx, sz);
                }
            }
            // 领地边带不爬高层结构（屋顶/高台）：高于中心层+2 的点夹到中心层，
            // 保证室内/地下时整圈领地贴「玩家所在连续层」而非浮在最高层天花板。
            if (!Double.isNaN(g[0])) {
                for (int k = 1; k < g.length; k++) {
                    if (Double.isNaN(g[k])) {
                        // 单点所在区块未载入 → 贴中心层。若不处理，NaN 顶点会把这圈方坪/
                        // 边带的几何整体拉成乱面（表现为「高度错乱」的另一个来源）。
                        g[k] = g[0];
                        continue;
                    }
                    if (g[k] > g[0] + 2.0) {
                        g[k] = g[0];
                    }
                }
            }
            ground[i] = g;
            // 是否可信地面：本地高度图优先，其次服务端下发的 groundY；
            // 两者都无效（未生成区块/虚空）→ 标记为「不渲染」。
            valid[i] = isPlausibleGround(world, g[0], z);
        }
    }

    /** 索引对应的据点是否有可信地面。 */
    private static boolean isValidIdx(int i) {
        return i >= 0 && i < valid.length && valid[i];
    }

    /**
     * 该据点是否有可信地面：取本地高度图（本地为 NaN 时退回服务端 groundY），
     * 必须高于世界底部才有意义 —— 「世界底部 + 3」是旧实现的无地形哨兵值，
     * 绝不能再当真实高度使用。
     */
    private static boolean isPlausibleGround(ClientWorld world, double localY, ZoneView zone) {
        double y = !Double.isNaN(localY) ? localY : zone.groundY();
        return !Double.isNaN(y) && y > world.getBottomY() + 2.0;
    }

    /**
     * 领地中心站面：优先「玩家所在连续层」（玩家在该领地内时）——室内/地下建筑
     * 中领地贴玩家脚下那层；玩家在领地外则取该处最高地表（远观整体高度）。
     */
    private static double centerGround(ClientWorld world, ZoneView z) {
        MinecraftClient mc = MinecraftClient.getInstance();
        double base = groundYAt(world, z.worldX(), z.worldZ());
        var p = mc.player;
        if (p == null) {
            return base;
        }
        boolean inside = Math.abs(p.getX() - z.worldX()) <= z.radius() + 1.5
                && Math.abs(p.getZ() - z.worldZ()) <= z.radius() + 1.5;
        if (!inside) {
            return base;
        }
        double pg = groundYAt(world, p.getX(), p.getZ());
        return Double.isNaN(pg) ? base : Math.min(base, pg);
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

    private static double centerY(int idx, double[] gs) {
        if (gs != null && !Double.isNaN(gs[0])) {
            return gs[0];
        }
        return 0;
    }

    // ================= 颜色 =================

    /** 区域基色（#44 主题：2042 科幻蓝绿）——攻方占点=荧光绿、守方稳固=电光蓝、
     *  争夺=青白呼吸。bright=true 给描边线用（更亮）。 */
    private static int[] areaColor(ZoneView zone, long timeMs, boolean bright) {
        Side owner = Side.values()[zone.ownerOrdinal()];
        float pulse = (float) ((timeMs % 800) / 800.0);
        if (owner == Side.ATTACKER) {
            return new int[]{58, 235, 150, bright ? 255 : 215};      // 攻方绿
        }
        if (owner == Side.DEFENDER && zone.meter() < 1e-3f) {
            return new int[]{66, 190, 255, bright ? 255 : 205};      // 守方电光蓝
        }
        // 争夺中：青 ↔ 白 呼吸（科幻感）
        int r = (int) (120 + 120 * pulse);
        int g = (int) (235 + 20 * pulse);
        int b = (int) (225 + 30 * pulse);
        int a = (int) ((bright ? 255 : 150) + 70 * pulse);
        return new int[]{r, g, Math.min(255, b), Math.min(255, a)};
    }

    /** 体积墙透明度：争夺/攻占中更亮并呼吸；守方稳固低一些。 */
    private static int wallAlpha(ZoneView zone, long timeMs) {
        Side owner = Side.values()[zone.ownerOrdinal()];
        float p = (float) ((timeMs % 1200) / 1200.0);
        if (owner == Side.ATTACKER) {
            return 120;
        }
        if (owner == Side.DEFENDER && zone.meter() < 1e-3f) {
            return 78;
        }
        return 120 + (int) (46 * Math.sin(p * Math.PI * 2.0));
    }

    /** 外晕呼吸强度（与主边带错相）。 */
    private static int glowAlpha(long timeMs) {
        float pulse = (float) ((timeMs % 1400) / 1400.0);
        return 40 + (int) (18 * Math.sin(pulse * Math.PI * 2.0));
    }
}
