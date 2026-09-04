package com.breakfront.client.hud;

import com.breakfront.client.state.ClientMatchState;
import com.breakfront.client.state.ClientMatchState.ZoneView;
import com.breakfront.game.Side;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.List;

/**
 * BF2042 式据点屏幕标记。
 *
 * 把当前扇区据点按「玩家相机」投影到 2D 屏幕：
 * - 视野内：在据点的屏幕投影位置绘制菱形 + 全局字母（A/B/C…），颜色随占领方，
 *   下方附距离米数；
 * - 视野外/身后：图标贴到屏幕边缘对应方向，半透明弱化，提示方位。
 * - 争夺中据点菱形外框白色呼吸闪烁。
 *
 * 纯 2D 几何绘制（菱形由 QUADS 拆成两半绘制），不引入贴图。
 */
public final class ZoneMarkers {

    private static final int INNER_MARGIN = 14;    // 判定"屏幕内"的收缩边距（避免图标贴死边缘）
    private static final int FONT_CENTER_Y = -4;   // 字母在菱形内的垂直微调

    private ZoneMarkers() {
    }

    public static void render(DrawContext ctx, TextRenderer font, int sw, int sh) {
        List<ZoneView> zones = ClientMatchState.zones();
        if (zones.isEmpty()) {
            return;
        }
        int phase = ClientMatchState.phaseOrdinal();
        if (phase != 1 && phase != 2) { // COUNTDOWN / BATTLE
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.getCameraEntity() == null) {
            return;
        }

        Vec3d cam = client.gameRenderer.getCamera().getPos();
        // 面朝方向（用 yaw/pitch 自行构造，规避映射差异）
        float yawR = (float) Math.toRadians(client.player.getYaw());
        float pitchR = (float) Math.toRadians(client.player.getPitch());
        Vec3d dir = new Vec3d(
                -Math.sin(yawR) * Math.cos(pitchR),
                -Math.sin(pitchR),
                Math.cos(yawR) * Math.cos(pitchR)).normalize();

        // 右向量与屏幕上向量（相机空间正交基）
        Vec3d right = dir.crossProduct(new Vec3d(0, 1, 0)).normalize();
        Vec3d up = right.crossProduct(dir).normalize();

        double vfov = Math.toRadians(client.options.getFov().getValue());
        double halfV = Math.tan(vfov / 2.0);
        double halfH = halfV * ((double) sw / sh);

        int cx = sw / 2;
        int cy = sh / 2;

        for (ZoneView zone : zones) {
            double wx = zone.worldX();
            double wz = zone.worldZ();
            double wy = zone.groundY() + 2.2; // 标记浮空于据点上空

            Vec3d to = new Vec3d(wx - cam.x, wy - cam.y, wz - cam.z);
            double fwd = to.dotProduct(dir);
            double dist = to.length();
            if (dist < 0.5) {
                continue;
            }
            double side = to.dotProduct(right);
            double upc = to.dotProduct(up);

            boolean behind = fwd < 0.12;
            double px, py;
            if (!behind) {
                double ndcX = side / fwd / halfH;
                double ndcY = upc / fwd / halfV;
                px = cx + ndcX * (sw / 2.0);
                py = cy - ndcY * (sh / 2.0);
            } else {
                // 身后：沿世界方向取反向延伸，落到屏幕外再夹取
                double sx = -side / (Math.max(0.12, -fwd)) / halfH;
                double sy = -upc / (Math.max(0.12, -fwd)) / halfV;
                px = cx + sx * (sw / 2.0);
                py = cy - sy * (sh / 2.0);
            }

            double inL = INNER_MARGIN;
            double inR = sw - INNER_MARGIN;
            double inT = INNER_MARGIN + 8;
            double inB = sh - INNER_MARGIN;

            boolean inside = px >= inL && px <= inR && py >= inT && py <= inB && !behind;
            double clampX = px, clampY = py;
            if (!inside) {
                // 从屏幕中心射向目标方向的射线与内框的交点（夹取到边缘）
                double dx = px - cx;
                double dy = py - cy;
                double t = 1.0;
                if (dx > 0) t = Math.min(t, (inR - cx) / dx);
                else if (dx < 0) t = Math.min(t, (inL - cx) / dx);
                if (dy > 0) t = Math.min(t, (inB - cy) / dy);
                else if (dy < 0) t = Math.min(t, (inT - cy) / dy);
                clampX = cx + dx * t;
                clampY = cy + dy * t;
            }

            drawZoneMarker(ctx, font, zone, clampX, clampY, dist, inside);
        }
    }

    private static void drawZoneMarker(DrawContext ctx, TextRenderer font, ZoneView zone,
                                       double px, double py, double dist, boolean inView) {
        Side owner = Side.values()[zone.ownerOrdinal()];
        boolean contested = owner == Side.DEFENDER && zone.meter() > 1e-3f;

        // 近大远小，但限制范围
        double size = 15.0 - dist * 0.012;
        size = Math.max(9.0, Math.min(17.0, size));
        if (!inView) {
            size = Math.max(8.0, size - 3.0);
        }

        long t = System.currentTimeMillis();
        float blink = (float) ((t % 700) / 700.0);

        int[] fillColor = markerFill(owner, contested, blink);
        int fx = fillColor[0], fy = fillColor[1], fz = fillColor[2], fa = fillColor[3];

        // 菱形底色
        drawDiamond(ctx, px, py, size + 2.0, size + 2.0, 0x00, 0x00, 0x00, 0xB0);
        // 状态色菱形（缩小一圈，形成黑描边视觉）
        drawDiamond(ctx, px, py, size, size, fx, fy, fz, fa);

        // 中央字母
        String letter = zone.letter().isEmpty() ? "?" : zone.letter();
        int lw = font.getWidth(letter);
        ctx.drawText(font, Text.literal(letter),
                (int) Math.round(px - lw / 2.0),
                (int) Math.round(py - font.fontHeight / 2.0 + FONT_CENTER_Y),
                0xFFFFFFFF, false);

        // 视野内显示距离（远于 400 米省略，避免噪点）
        if (inView && dist <= 400) {
            String dm = (int) Math.round(dist) + "m";
            int dw = font.getWidth(dm);
            ctx.drawText(font, Text.literal(dm),
                    (int) Math.round(px - dw / 2.0),
                    (int) Math.round(py + size + 2.0),
                    0xA0FFFFFF, false);
        }
    }

    private static int[] markerFill(Side owner, boolean contested, float blink) {
        if (owner == Side.ATTACKER) {
            return new int[]{240, 122, 60, 235}; // 攻方橙
        }
        if (contested) {
            // 争夺中：白↔淡橙闪烁
            int v = (int) (200 - 80 * blink);
            return new int[]{v, (int) (200 - 40 * blink), (int) (210 - 110 * blink), 230};
        }
        return new int[]{64, 145, 255, 230}; // 守方蓝
    }

    /** 以 QUADS 画一个菱形（对角线水平/垂直）。半透 + 黑底描边由调用层叠加实现。 */
    private static void drawDiamond(DrawContext ctx, double cx, double cy,
                                    double halfW, double halfH,
                                    int r, int g, int b, int a) {
        if (halfW <= 0 || halfH <= 0) {
            return;
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        Matrix4f m = ctx.getMatrices().peek().getPositionMatrix();
        float fr = r / 255f, fg = g / 255f, fb = b / 255f, fa = a / 255f;
        BufferBuilder buf = Tessellator.getInstance()
                .begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        buf.vertex(m, (float) cx, (float) (cy - halfH), 0).color(fr, fg, fb, fa);
        buf.vertex(m, (float) (cx + halfW), (float) cy, 0).color(fr, fg, fb, fa);
        buf.vertex(m, (float) cx, (float) (cy + halfH), 0).color(fr, fg, fb, fa);
        buf.vertex(m, (float) (cx - halfW), (float) cy, 0).color(fr, fg, fb, fa);
        BufferRenderer.drawWithGlobalProgram(buf.end());
        RenderSystem.disableBlend();
    }
}
