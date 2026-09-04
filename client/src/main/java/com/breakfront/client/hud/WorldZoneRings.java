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
 * 据点区域「描边高亮」（世界空间渲染）。
 *
 * 在 BATTLE/COUNTDOWN 阶段，对当前扇区每个据点画一圈贴近地面的圆环线，
 * 颜色随占领方：守方蓝 / 攻方橙 / 争夺中白色呼吸闪烁。
 * 由 BreakfrontClient 注册到 WorldRenderEvents；数据每帧从 ClientMatchState 同步。
 *
 * 渲染路径说明：使用 vanilla 标准 immediate 线渲染（BufferBuilder + 官方 shader），
 * 与 Sodium/VulkanMod 的自带渲染管线兼容 —— 它们接管的是区块/实体渲染，不拦截此类调用。
 */
public final class WorldZoneRings {

    private static final int SEGMENTS = 48;

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
        for (ZoneView zone : zones) {
            int[] rgb = ringColor(zone, t);
            drawRing(m, zone.worldX(), zone.groundY() + 0.12, zone.worldZ(),
                    zone.radius(), rgb[0], rgb[1], rgb[2], rgb[3]);
        }
    }

    private static int[] ringColor(ZoneView zone, long timeMs) {
        Side owner = Side.values()[zone.ownerOrdinal()];
        float pulse = (float) ((timeMs % 900) / 900.0);
        if (owner == Side.ATTACKER) {
            return new int[]{232, 98, 44, 235};
        }
        if (owner == Side.DEFENDER && zone.meter() < 1e-3f) {
            return new int[]{70, 150, 255, 220};
        }
        // 争夺中：白→橙呼吸
        int a = 140 + (int) (90 * pulse);
        int r = 255;
        int g = (int) (220 - 90 * pulse);
        int b = (int) (140 - 90 * pulse);
        return new int[]{r, g, b, a};
    }

    private static void drawRing(Matrix4f m, double cx, double y, double cz,
                                 double radius, int r, int g, int b, int a) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getRenderTypeLinesProgram);
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
        RenderSystem.disableBlend();
    }
}
