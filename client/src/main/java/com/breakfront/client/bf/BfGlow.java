package com.breakfront.client.bf;

import net.minecraft.client.gui.DrawContext;

/**
 * 自然辉光（#44 主题）：为「青绿强调色块」叠加多层半透明外扩光晕。
 *
 * <p>实现：从核心向外 1..radius 像素逐层画扩展矩形，越外越淡
 * （alpha = strength/(layer+1)），叠加后呈现柔和的荧光扩散——比单层半透明
 * 更接近"发光材质"观感，且不依赖 shader，几何矢量体系内即可用。
 */
public final class BfGlow {

    private BfGlow() {
    }

    /**
     * 在矩形外扩散辉光（核心块仍由调用方绘制）。
     *
     * @param rgb      不含 alpha 的 RGB（如 BfTheme.TEAL &amp; 0xFFFFFF）
     * @param strength 辉光强度（建议 40-120）
     * @param radius   辉光扩散半径 px（建议 5-10）
     */
    public static void rect(DrawContext ctx, int x, int y, int w, int h,
                            int rgb, int strength, int radius) {
        if (strength <= 0 || radius <= 0) {
            return;
        }
        int rr = (rgb >> 16) & 0xFF;
        int gg = (rgb >> 8) & 0xFF;
        int bb = rgb & 0xFF;
        for (int i = radius; i >= 1; i--) {
            int alpha = strength / (i + 1);
            if (alpha <= 2) {
                continue;
            }
            int col = (Math.min(255, alpha) << 24) | (rr << 16) | (gg << 8) | bb;
            ctx.fill(x - i, y - i, x + w + i, y + h + i, col);
        }
    }

    /**
     * 细线/细条辉光（导航下划线、进度条、顶栏强调线用）。
     * 自动把厚度<4px 的条按外扩方向垫出光晕。
     */
    public static void strip(DrawContext ctx, int x, int y, int w, int h,
                             int rgb, int strength) {
        rect(ctx, x, y, w, Math.max(2, h), rgb, strength, Math.max(4, h * 3));
    }
}
