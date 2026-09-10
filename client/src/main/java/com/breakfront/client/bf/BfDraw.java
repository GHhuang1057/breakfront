package com.breakfront.client.bf;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import org.joml.Matrix4f;

/**
 * 矢量绘制原语：只使用几何（色块/渐变/斜切平行四边形/菱形），
 * 不依赖任何像素贴图 —— BREAKFRONT 全界面渲染的底层。
 * 说明：GUI 阶段绘制即时几何走 vanilla BufferBuilder + 官方 shader，兼容 VulkanMod/Sodium。
 */
public final class BfDraw {

    private BfDraw() {
    }

    public static void fill(DrawContext ctx, int x, int y, int w, int h, int argb) {
        if (w > 0 && h > 0) {
            ctx.fill(x, y, x + w, y + h, argb);
        }
    }

    /** 纵向平滑渐变（逐行插值）。 */
    public static void gradientV(DrawContext ctx, int x, int y, int w, int h,
                                 int top, int bottom) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int[] a = BfTheme.rgba(top);
        int[] b = BfTheme.rgba(bottom);
        for (int i = 0; i < h; i++) {
            double t = (double) i / h;
            int r = (int) (a[0] + (b[0] - a[0]) * t);
            int g = (int) (a[1] + (b[1] - a[1]) * t);
            int bl = (int) (a[2] + (b[2] - a[2]) * t);
            int al = (int) (a[3] + (b[3] - a[3]) * t);
            ctx.fill(x, y + i, x + w, y + i + 1, (al << 24) | (r << 16) | (g << 8) | bl);
        }
    }

    /** 横向平滑渐变（逐列插值）。用于氛围暗角（左/右缘）。 */
    public static void gradientH(DrawContext ctx, int x, int y, int w, int h,
                                 int left, int right) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int[] a = BfTheme.rgba(left);
        int[] b = BfTheme.rgba(right);
        for (int i = 0; i < w; i++) {
            double t = (double) i / w;
            int r = (int) (a[0] + (b[0] - a[0]) * t);
            int g = (int) (a[1] + (b[1] - a[1]) * t);
            int bl = (int) (a[2] + (b[2] - a[2]) * t);
            int al = (int) (a[3] + (b[3] - a[3]) * t);
            ctx.fill(x + i, y, 1, h, (al << 24) | (r << 16) | (g << 8) | bl);
        }
    }

    /** 斜切平行四边形（BF 面板语言）：上边相对下边右移 slant 像素。 */
    public static void parallelogram(DrawContext ctx, int x, int y, int w, int h, int slant, int argb) {
        quad(ctx,
                x + slant, y,
                x + w + slant, y,
                x + w, y + h,
                x, y + h, argb);
    }

    /** 任意凸四边形（色块）。 */
    public static void quad(DrawContext ctx,
                            double x0, double y0, double x1, double y1,
                            double x2, double y2, double x3, double y3, int argb) {
        int[] c = BfTheme.rgba(argb);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        Matrix4f m = ctx.getMatrices().peek().getPositionMatrix();
        BufferBuilder buf = Tessellator.getInstance()
                .begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        buf.vertex(m, (float) x0, (float) y0, 0).color(c[0] / 255f, c[1] / 255f, c[2] / 255f, c[3] / 255f);
        buf.vertex(m, (float) x1, (float) y1, 0).color(c[0] / 255f, c[1] / 255f, c[2] / 255f, c[3] / 255f);
        buf.vertex(m, (float) x2, (float) y2, 0).color(c[0] / 255f, c[1] / 255f, c[2] / 255f, c[3] / 255f);
        buf.vertex(m, (float) x3, (float) y3, 0).color(c[0] / 255f, c[1] / 255f, c[2] / 255f, c[3] / 255f);
        BufferRenderer.drawWithGlobalProgram(buf.end());
        RenderSystem.disableBlend();
    }

    public static void diamond(DrawContext ctx, double cx, double cy, double half, int argb) {
        quad(ctx, cx, cy - half, cx + half, cy, cx, cy + half, cx - half, cy, argb);
    }

    /**
     * 矢量圆环（环形带，无贴图）：以 TRIANGLE_STRIP 画 64 段的环。
     * 用于击杀反馈环（对标 GD656 IconRingEffect 的三角带环 + 发光/插值动效）。
     *
     * @param radius    环中心半径（px）
     * @param thickness 环带厚度（px），<=0 跳过
     * @param argb      含 alpha 的颜色
     */
    public static void ring(DrawContext ctx, double cx, double cy, double radius, double thickness, int argb) {
        if (radius <= 0 || thickness <= 0) {
            return;
        }
        int[] c = BfTheme.rgba(argb);
        if (c[3] <= 0) {
            return;
        }
        float rOuter = (float) (radius + thickness * 0.5);
        float rInner = (float) Math.max(0.0, radius - thickness * 0.5);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        Matrix4f m = ctx.getMatrices().peek().getPositionMatrix();
        BufferBuilder buf = Tessellator.getInstance()
                .begin(VertexFormat.DrawMode.TRIANGLE_STRIP, VertexFormats.POSITION_COLOR);
        int SEG = 64;
        for (int i = 0; i <= SEG; i++) {
            double ang = Math.PI * 2.0 * i / SEG;
            float cos = (float) Math.cos(ang);
            float sin = (float) Math.sin(ang);
            buf.vertex(m, (float) (cx + cos * rOuter), (float) (cy + sin * rOuter), 0)
                    .color(c[0] / 255f, c[1] / 255f, c[2] / 255f, c[3] / 255f);
            buf.vertex(m, (float) (cx + cos * rInner), (float) (cy + sin * rInner), 0)
                    .color(c[0] / 255f, c[1] / 255f, c[2] / 255f, c[3] / 255f);
        }
        BufferRenderer.drawWithGlobalProgram(buf.end());
        RenderSystem.disableBlend();
    }

    /** 1px 描边矩形（卡片边框语言）。 */
    public static void border(DrawContext ctx, int x, int y, int w, int h, int argb) {
        fill(ctx, x, y, w, 1, argb);
        fill(ctx, x, y + h - 1, w, 1, argb);
        fill(ctx, x, y + 1, 1, h - 2, argb);
        fill(ctx, x + w - 1, y + 1, 1, h - 2, argb);
    }

    /** 细进度条。progress<0 = 不定态（亮块循环扫动）。 */
    public static void progressBar(DrawContext ctx, int x, int y, int w, int h,
                                   double progress, long timeMs, int argb) {
        fill(ctx, x, y, w, h, 0x22FFFFFF);
        if (progress >= 0) {
            fill(ctx, x, y, (int) (w * Math.min(1, Math.max(0, progress))), h, argb);
        } else {
            int block = Math.max(24, w / 4);
            int span = w + block;
            int off = (int) ((timeMs % 1600) / 1600.0 * span);
            fill(ctx, x + off - block, y, block, h, argb);
        }
    }

    // ---------- 城市天际线（战场氛围背景，确定性生成 + 循环平移视差） ----------

    private static final int SKY_W = 1600;
    private static final int[][] SKY_FAR = genSkyline(777L, 46, 6, 16, 24, 78);
    private static final int[][] SKY_NEAR = genSkyline(424242L, 34, 10, 24, 38, 120);

    /** 每栋楼 {x, w, h}，x 覆盖 [0,SKY_W)。 */
    private static int[][] genSkyline(long seed, int count, int minW, int maxW, int minH, int maxH) {
        java.util.Random r = new java.util.Random(seed);
        int[][] out = new int[count][3];
        for (int i = 0; i < count; i++) {
            out[i][0] = (int) (r.nextDouble() * SKY_W);
            out[i][1] = minW + r.nextInt(Math.max(1, maxW - minW));
            out[i][2] = minH + r.nextInt(Math.max(1, maxH - minH));
        }
        return out;
    }

    /**
     * 画一层城市剪影（水平无缝循环 + 视差偏移）。
     *
     * @param offsetPx 随时间递增的偏移（层速度不同 → 视差）
     */
    public static void skyline(DrawContext ctx, int baseY, double offsetPx, int[][] buildings, int argb) {
        int off = (int) (offsetPx % SKY_W);
        if (off < 0) {
            off += SKY_W;
        }
        for (int rep = -1; rep <= 1; rep++) {
            int baseX = rep * SKY_W - off;
            for (int[] b : buildings) {
                int x = baseX + b[0];
                fill(ctx, x, baseY - b[2], b[1], b[2], argb);
            }
        }
    }

    public static int[][] skylineFar() {
        return SKY_FAR;
    }

    public static int[][] skylineNear() {
        return SKY_NEAR;
    }
}
