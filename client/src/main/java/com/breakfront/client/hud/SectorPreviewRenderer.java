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
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        var world = mc.world;
        if (world == null) {
            return;
        }
        Matrix4f m = context.positionMatrix();
        int cur = SectorEditState.currentSector();
        for (ZoneView zone : zones) {
            // ⚠️ 无有效地面（坐标落在未生成区块/虚空）→ 跳过：以前会拿旧版服务端下发的
            // bottomY+3 假高度画在基岩层，导致编辑器里「圈全都不见了/位置错乱」。
            double gy = zone.groundY();
            if (Double.isNaN(gy) || gy <= world.getBottomY() + 2.0) {
                continue;
            }
            int[] rgb = PALETTE[(zone.sectorIndex() & 0x7fffffff) % PALETTE.length];
            boolean active = zone.sectorIndex() == cur;
            int a = active ? 255 : 180;
            // 与服务端方形判定（|dx|≤r 且 |dz|≤r）保持一致：画方框而非圆环，
            // 避免"看到的范围"和"实际占领判定范围"对不上。
            drawSquare(m, zone.worldX(), gy + 0.12, zone.worldZ(),
                    zone.radius(), rgb, a, active ? 2.0 : 1.0);
            if (active) {
                drawCenterMark(m, zone.worldX(), gy + 0.22, zone.worldZ(), rgb);
            }
        }
        // 出生点标记：攻=橙、守=蓝、大厅=白圈，让 admin 在编辑器里看得见、对得准
        drawSpawn(m, world, SectorEditState.attackerSpawnX(), SectorEditState.attackerSpawnZ(),
                new int[]{232, 98, 44}, "攻");
        drawSpawn(m, world, SectorEditState.defenderSpawnX(), SectorEditState.defenderSpawnZ(),
                new int[]{77, 166, 255}, "守");
        drawSpawn(m, world, SectorEditState.lobbySpawnX(), SectorEditState.lobbySpawnZ(),
                new int[]{234, 242, 248}, "厅");
    }

    /** 在地面画一个「柱子 + 顶标」的出生点标记（无地面则跳过）。 */
    private static void drawSpawn(Matrix4f m, net.minecraft.client.world.ClientWorld world,
                                 double x, double z, int[] rgb, String label) {
        if (Double.isNaN(x) || Double.isNaN(z)) {
            return;
        }
        int top = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING,
                (int) Math.floor(x), (int) Math.floor(z));
        if (top <= world.getBottomY() + 2) {
            return;
        }
        double gy = top + 0.12;
        float rf = rgb[0] / 255f, gf = rgb[1] / 255f, bf = rgb[2] / 255f;
        // 立柱（细竖线，高 2.2 格，醒目）
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getRenderTypeLinesProgram);
        BufferBuilder col = Tessellator.getInstance()
                .begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.LINES);
        col.vertex(m, (float) x, (float) gy, (float) z).color(rf, gf, bf, 1f).normal(0, 1, 0);
        col.vertex(m, (float) x, (float) (gy + 2.2), (float) z).color(rf, gf, bf, 1f).normal(0, 1, 0);
        BufferRenderer.drawWithGlobalProgram(col.end());
        RenderSystem.disableBlend();
        // 地面圆环（半径 2.5 格）表示出生散布区
        drawGroundRing(m, x, gy, z, 2.5, rgb, 200);
        // 顶标菱形
        drawCenterMark(m, x, gy + 2.3, z, rgb);
    }

    /** 贴地圆环（细线段近似）。 */
    private static void drawGroundRing(Matrix4f m, double cx, double y, double cz,
                                       double r, int[] rgb, int a) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getRenderTypeLinesProgram);
        RenderSystem.lineWidth(2.0f);
        BufferBuilder buffer = Tessellator.getInstance()
                .begin(VertexFormat.DrawMode.DEBUG_LINE_STRIP, VertexFormats.LINES);
        float rf = rgb[0] / 255f, gf = rgb[1] / 255f, bf = rgb[2] / 255f;
        float af = a / 255f;
        int seg = 28;
        for (int i = 0; i <= seg; i++) {
            double ang = i * (Math.PI * 2.0 / seg);
            buffer.vertex(m, (float) (cx + Math.cos(ang) * r), (float) y, (float) (cz + Math.sin(ang) * r))
                    .color(rf, gf, bf, af).normal(0.0f, 1.0f, 0.0f);
        }
        BufferRenderer.drawWithGlobalProgram(buffer.end());
        RenderSystem.lineWidth(1.0f);
        RenderSystem.disableBlend();
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

    /** 方形边界框（与服务端 |dx|≤r 且 |dz|≤r 的判定一致）。 */
    private static void drawSquare(Matrix4f m, double cx, double y, double cz,
                                   double radius, int[] rgb, int a, double width) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getRenderTypeLinesProgram);
        RenderSystem.lineWidth((float) width);
        BufferBuilder buffer = Tessellator.getInstance()
                .begin(VertexFormat.DrawMode.DEBUG_LINE_STRIP, VertexFormats.LINES);
        float rf = rgb[0] / 255f;
        float gf = rgb[1] / 255f;
        float bf = rgb[2] / 255f;
        float af = a / 255f;
        double[][] pts = {
                {cx - radius, cz - radius},
                {cx + radius, cz - radius},
                {cx + radius, cz + radius},
                {cx - radius, cz + radius},
                {cx - radius, cz - radius},
        };
        for (double[] p : pts) {
            buffer.vertex(m, (float) p[0], (float) y, (float) p[1])
                    .color(rf, gf, bf, af)
                    .normal(0.0f, 1.0f, 0.0f);
        }
        BufferRenderer.drawWithGlobalProgram(buffer.end());
        RenderSystem.lineWidth(1.0f);
        RenderSystem.disableBlend();
    }
}
