package com.breakfront.client.hud;

import com.breakfront.client.state.SectorEditState;
import com.breakfront.client.state.SectorEditState.ZoneView;
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
 * 扇区编辑器预览（世界空间渲染）：打开 /bfs 后，为布局中每个据点画地面圆环
 * （按扇区分色，当前扇区加粗高亮 + 中心菱形标记），帮助在地图上定位划分。
 *
 * 渲染路径与 WorldZoneRings 一致（immediate 线渲染），兼容 Sodium/VulkanMod。
 */
public final class SectorPreviewRenderer {

    private static final int SEGMENTS = 48;

    // 扇区分色轮盘（BF 语义近似：攻/守蓝橙之外顺延绿、黄…）
    private static final int[][] PALETTE = {
            {232, 98, 44},    // 橙（扇区一/进攻侧）
            {70, 150, 255},   // 蓝
            {95, 206, 106},   // 绿
            {240, 212, 74},   // 黄
            {255, 74, 60},    // 红
            {178, 120, 255},  // 紫
            {0, 210, 220},    // 青
            {255, 160, 90}    // 浅橙
    };

    private SectorPreviewRenderer() {
    }

    public static void render(WorldRenderContext context) {
        if (!SectorEditState.enabled()) {
            return;
        }
        List<ZoneView> zones = SectorEditState.zones();
        if (zones.isEmpty()) {
            return;
        }
        Matrix4f m = context.positionMatrix();
        int cur = SectorEditState.currentSector();
        for (ZoneView zone : zones) {
            int[] rgb = PALETTE[(zone.sectorIndex() & 0x7fffffff) % PALETTE.length];
            boolean active = zone.sectorIndex() == cur;
            int a = active ? 255 : 180;
            drawRing(m, zone.worldX(), zone.groundY() + 0.12, zone.worldZ(),
                    zone.radius(), rgb[0], rgb[1], rgb[2], a, active ? 2.0 : 1.0);
            if (active) {
                drawCenterMark(m, zone.worldX(), zone.groundY() + 0.22, zone.worldZ(), rgb);
            }
        }
    }

    /** 当前扇区圆心处的小菱形（两条交叉线段），表示「正在往这里加据点」。 */
    private static void drawCenterMark(Matrix4f m, double cx, double y, double cz, int[] rgb) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getRenderTypeLinesProgram);
        BufferBuilder buffer = Tessellator.getInstance()
                .begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.LINES);
        float rf = rgb[0] / 255f;
        float gf = rgb[1] / 255f;
        float bf = rgb[2] / 255f;
        double d = 1.2;
        buffer.vertex(m, (float) (cx - d), (float) y, (float) cz).color(rf, gf, bf, 1f).normal(0, 1, 0);
        buffer.vertex(m, (float) (cx + d), (float) y, (float) cz).color(rf, gf, bf, 1f).normal(0, 1, 0);
        buffer.vertex(m, (float) cx, (float) y, (float) (cz - d)).color(rf, gf, bf, 1f).normal(0, 1, 0);
        buffer.vertex(m, (float) cx, (float) y, (float) (cz + d)).color(rf, gf, bf, 1f).normal(0, 1, 0);
        BufferRenderer.drawWithGlobalProgram(buffer.end());
        RenderSystem.disableBlend();
    }

    private static void drawRing(Matrix4f m, double cx, double y, double cz,
                                 double radius, int r, int g, int b, int a, double width) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getRenderTypeLinesProgram);
        RenderSystem.lineWidth((float) width);
        BufferBuilder buffer = Tessellator.getInstance()
                .begin(VertexFormat.DrawMode.DEBUG_LINE_STRIP, VertexFormats.LINES);
        double step = Math.PI * 2.0 / SEGMENTS;
        float rf = r / 255f;
        float gf = g / 255f;
        float bf = b / 255f;
        float af = a / 255f;
        for (int i = 0; i <= SEGMENTS; i++) {
            double ang = i * step;
            buffer.vertex(m, (float) (cx + radius * Math.cos(ang)), (float) y,
                            (float) (cz + radius * Math.sin(ang)))
                    .color(rf, gf, bf, af)
                    .normal(0.0f, 1.0f, 0.0f);
        }
        BufferRenderer.drawWithGlobalProgram(buffer.end());
        RenderSystem.lineWidth(1.0f);
        RenderSystem.disableBlend();
    }
}
