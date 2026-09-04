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
}
