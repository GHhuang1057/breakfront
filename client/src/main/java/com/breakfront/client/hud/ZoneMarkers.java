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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 地标（BF2042 式）重做版 —— 两块内容：
 *
 * 1) 固定目标带（renderRailInto）：主菜单顶栏中段绘制的菱形序列。
 *    每个据点一颗菱形，内部字母颜色随占领方；菱形外侧一圈「进度刻度」——
 *    防守方安定=淡蓝圈、攻方已占=满圈黄、争夺中=白/橙呼吸 + 按攻方推进度
 *    meter 亮起相应弧段。进度做逐帧平滑，不跳变。
 *
 * 2) 屏缘方位箭头（render）：据点中心不在视窗/超出距离时，贴屏幕边缘画
 *    对应方向的小菱形箭头（颜色同占领方），同一侧多条按序错开，不再重叠
 *    成团。据点实际位置改由世界空间「方块描边」高亮表达，屏幕不再堆浮标。
 *
 * 纯 2D 几何绘制（QUADS 拆菱形 + fill 画刻度），不引入贴图。
 */
public final class ZoneMarkers {

    /** 顶栏尺寸（BreakfrontHud 共用，保证目标带与顶栏对齐）。 */
    public static final int PILL_TOP = 6;
    public static final int PILL_H = 32;

    private static final int RING_TICKS = 24;       // 进度刻度颗粒数
    private static final int FONT_CENTER_Y = -4;

    /** 据点推进度平滑缓冲（zoneId -> 当前显示值 0..1）。 */
    private static final Map<String, Float> smoothMeter = new HashMap<>();

    private ZoneMarkers() {
    }

    // ================= 屏缘方位箭头 =================

    public static void render(DrawContext ctx, TextRenderer font, int sw, int sh) {
        List<ZoneView> zones = ClientMatchState.zones();
        if (zones.isEmpty()) {
            return;
        }
        int phase = ClientMatchState.phaseOrdinal();
        if (phase != 1 && phase != 2) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.getCameraEntity() == null) {
            return;
        }
        Vec3d cam = client.gameRenderer.getCamera().getPos();
        float yawR = (float) Math.toRadians(client.player.getYaw());
        float pitchR = (float) Math.toRadians(client.player.getPitch());
        Vec3d dir = new Vec3d(
                -Math.sin(yawR) * Math.cos(pitchR),
                -Math.sin(pitchR),
                Math.cos(yawR) * Math.cos(pitchR)).normalize();
        Vec3d right = dir.crossProduct(new Vec3d(0, 1, 0)).normalize();
        Vec3d up = right.crossProduct(dir).normalize();
        double vfov = Math.toRadians(client.options.getFov().getValue());
        double halfV = Math.tan(vfov / 2.0);
        double halfH = halfV * ((double) sw / sh);
        int cx = sw / 2;
        int cy = sh / 2;

        // 错开槽位：每条屏缘记录已占用的 y/x，防止同侧箭头重叠
        int[] edgeCursor = new int[4]; // 0右 1左 2下 3上
        for (ZoneView zone : zones) {
            double wx = zone.worldX();
            double wz = zone.worldZ();
            double wy = zone.groundY() + 2.0;
            Vec3d to = new Vec3d(wx - cam.x, wy - cam.y, wz - cam.z);
            double dist = to.length();
            if (dist < 4.0) {
                continue;
            }
            double fwd = to.dotProduct(dir);
            double side = to.dotProduct(right);
            double upc = to.dotProduct(up);
            double px;
            double py;
            if (fwd > 0.12) {
                px = cx + (side / fwd / halfH) * (sw / 2.0);
                py = cy - (upc / fwd / halfV) * (sh / 2.0);
            } else {
                double sx = -side / Math.max(0.12, -fwd) / halfH;
                double sy = -upc / Math.max(0.12, -fwd) / halfV;
                px = cx + sx * (sw / 2.0);
                py = cy - sy * (sh / 2.0);
            }
            double inL = 34;
            double inR = sw - 34;
            double inT = PILL_TOP + PILL_H + 14;
            double inB = sh - 30;
            boolean inside = px >= inL && px <= inR && py >= inT && py <= inB && fwd > 0.12;
            if (inside) {
                continue; // 可见：世界方块描边已足够定位，不叠屏幕浮标
            }
            // 贴边并错开
            double dx = px - cx;
            double dy = py - cy;
            int edge = dx > 0 ? 0 : (dx < 0 ? 1 : (dy > 0 ? 2 : 3));
            double t = 1.0;
            if (edge == 0) t = Math.min(t, (inR - cx) / Math.max(dx, 0.001));
            else if (edge == 1) t = Math.min(t, (inL - cx) / Math.min(dx, -0.001));
            else if (edge == 2) t = Math.min(t, (inB - cy) / Math.max(dy, 0.001));
            else t = Math.min(t, (inT - cy) / Math.min(dy, -0.001));
            double ex = cx + dx * t;
            double ey = cy + dy * t;
            // 沿边缘滑动槽位（水平边错 x，垂直边错 y）
            if (edge == 0) { ey = Math.min(inB - 18, Math.max(inT + 8, inT + 8 + edgeCursor[0] * 26)); edgeCursor[0]++; }
            else if (edge == 1) { ey = Math.min(inB - 18, Math.max(inT + 8, inT + 8 + edgeCursor[1] * 26)); edgeCursor[1]++; }
            else if (edge == 2) { ex = Math.min(inR - 26, Math.max(inL + 26, cx - (inR - inL) / 2 + edgeCursor[2] * 30)); edgeCursor[2]++; }
            else { ex = Math.min(inR - 26, Math.max(inL + 26, cx - (inR - inL) / 2 + edgeCursor[3] * 30)); edgeCursor[3]++; }

            drawEdgeArrow(ctx, font, zone, ex, ey);
        }
    }

    private static void drawEdgeArrow(DrawContext ctx, TextRenderer font, ZoneView zone, double px, double py) {
        long now = System.currentTimeMillis();
        Side owner = Side.values()[zone.ownerOrdinal()];
        boolean contested = owner == Side.DEFENDER && zone.meter() > 1e-3f;
        int[] col = markerColor(owner, contested, now);
        int blink = (now % 600) < 300 ? 0 : 36; // 争夺时轻微闪烁提示
        int a = Math.max(120, col[3] - (contested ? blink : 40));
        // 指向屏幕外侧的箭头（三颗菱形拼出的方向提示，指向 px,py 的外侧）
        int dirX = px >= MinecraftClient.getInstance().getWindow().getScaledWidth() / 2.0 ? 1 : -1;
        drawDiamond(ctx, px - dirX * 6, py, 9, 9, 0, 0, 0, 0x90);
        drawDiamond(ctx, px - dirX * 6, py, 6.4, 6.4, col[0], col[1], col[2], a);
        // 收尖（朝屏幕外的小菱形）
        drawDiamond(ctx, px + dirX * 2, py, 3.6, 3.6, col[0], col[1], col[2], a);
        // 字母（暗底描边提升可读）
        String letter = zone.letter();
        int lw = font.getWidth(letter);
        ctx.fill((int) px - lw / 2 - 3, (int) py - font.fontHeight / 2 - 2,
                (int) px + lw / 2 + 3, (int) py + font.fontHeight / 2 + 2, 0xAA0A0D12);
        ctx.drawText(font, Text.literal(letter), (int) px - lw / 2,
                (int) py - font.fontHeight / 2 + FONT_CENTER_Y + 2, 0xFFFFFFFF, false);
    }

    // ================= 固定目标带（顶栏中段菱形序列） =================

    /**
     * 在顶栏中央区域绘制据点菱形序列。x/y/w/h 为可用带区（BreakfrontHud 传入）。
     * 菱形带颜色字母 + 外侧进度刻度环（平滑）。返回实际占用的水平宽度。
     */
    public static int renderRailInto(DrawContext ctx, TextRenderer font,
                                     int x, int y, int w, int h, List<ZoneView> zones) {
        if (zones.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        int count = Math.min(zones.size(), 8);
        int dia = Math.min(22, h - 6);             // 菱形外接圆直径
        int tickR = dia / 2 + 4;                    // 刻度环半径
        int total = count * (tickR * 2 + 2) - 2;
        int cx0 = x + Math.max(0, (w - total) / 2);
        int cy0 = y + h / 2;
        int used = 0;
        for (int i = 0; i < count; i++) {
            ZoneView zone = zones.get(i);
            int cx = cx0 + i * (tickR * 2 + 2);
            drawRailDiamond(ctx, font, cx, cy0, dia / 2, tickR, zone, now);
            used += tickR * 2 + 2;
        }
        return used;
    }

    private static void drawRailDiamond(DrawContext ctx, TextRenderer font,
                                        int cx, int cy, double half, int tickR,
                                        ZoneView zone, long now) {
        Side owner = Side.values()[zone.ownerOrdinal()];
        boolean contested = owner == Side.DEFENDER && zone.meter() > 1e-3f;
        int[] col = markerColor(owner, contested, now);
        float target = Math.max(0f, Math.min(1f, zone.meter()));
        float shown = smoothMeter.compute(zone.zoneId(), (k, v) -> v == null
                ? target
                : v + (target - v) * 0.18f);
        if (Math.abs(shown - target) < 0.002f && target < 1e-3f) {
            shown = 0f;
        }
        smoothMeter.put(zone.zoneId(), shown);

        // 底影菱形 + 状态色菱形（黑边视觉）
        drawDiamond(ctx, cx, cy, half + 1.2, half + 1.2, 0, 0, 0, 0xB0);
        int flicker = contested && (now % 500) < 250 ? 46 : 0;
        drawDiamond(ctx, cx, cy, half, half,
                Math.min(255, col[0] + flicker),
                Math.min(255, col[1] + flicker),
                Math.min(255, col[2] + flicker),
                Math.min(255, col[3] + flicker));
        // 字母（黑/白按底色亮度）
        String letter = zone.letter();
        int lw = font.getWidth(letter);
        int textCol = (col[0] * 3 + col[1] * 6 + col[2]) > 1500 ? 0xFF0A0D12 : 0xFFFFFFFF;
        ctx.drawText(font, Text.literal(letter),
                cx - lw / 2, cy - font.fontHeight / 2 + FONT_CENTER_Y,
                textCol, false);

        // 刻度环：争夺中显示推进弧；安定显示一圈弱色；攻方已占满圈亮黄
        int ticks = RING_TICKS;
        int active = (int) Math.ceil(shown * ticks);
        int ringColor;
        int ringA;
        if (owner == Side.ATTACKER) {
            ringColor = 0xF5D44A;
            ringA = 235;
            active = ticks;
        } else if (!contested) {
            ringColor = 0x4C9EFF;
            ringA = 90;
            active = 0;
        } else {
            ringColor = (now % 500) < 250 ? 0xFFFFFF : 0xF5D44A;
            ringA = 235;
        }
        for (int k = 0; k < ticks; k++) {
            double ang = -Math.PI / 2 + Math.PI * 2.0 * k / ticks;
            int px = (int) Math.round(cx + tickR * Math.cos(ang));
            int py = (int) Math.round(cy + tickR * Math.sin(ang));
            boolean on = k < active;
            int a = on ? ringA : (contested ? 60 : ringA / 2);
            ctx.fill(px - 1, py - 1, px + 2, py + 2,
                    on ? rgba(ringColor, a) : rgba(ringColor, Math.max(30, a)));
        }
    }

    // ================= 几何工具 =================

    private static int[] markerColor(Side owner, boolean contested, long now) {
        if (owner == Side.ATTACKER) {
            return new int[]{245, 212, 74, 245};  // 攻方黄
        }
        if (contested) {
            int pulse = (int) (180 + 60 * Math.sin(now / 90.0));
            return new int[]{255, pulse, pulse / 2, 240}; // 争夺白→橙呼吸
        }
        return new int[]{76, 158, 255, 240};      // 守方蓝
    }

    /** 以 QUADS 画一个菱形（对角线水平/垂直）。半透 + 黑底描边由调用层叠加实现。 */
    private static void drawDiamond(DrawContext ctx, double cx, double cy,
                                    double halfW, double halfH,
                                    int r, int g, int b, int a) {
        if (halfW <= 0 || halfH <= 0 || a <= 0) {
            return;
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        Matrix4f m = ctx.getMatrices().peek().getPositionMatrix();
        float fr = r / 255f;
        float fg = g / 255f;
        float fb = b / 255f;
        float fa = Math.min(1f, a / 255f);
        BufferBuilder buf = Tessellator.getInstance()
                .begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        buf.vertex(m, (float) cx, (float) (cy - halfH), 0).color(fr, fg, fb, fa);
        buf.vertex(m, (float) (cx + halfW), (float) cy, 0).color(fr, fg, fb, fa);
        buf.vertex(m, (float) cx, (float) (cy + halfH), 0).color(fr, fg, fb, fa);
        buf.vertex(m, (float) (cx - halfW), (float) cy, 0).color(fr, fg, fb, fa);
        BufferRenderer.drawWithGlobalProgram(buf.end());
        RenderSystem.disableBlend();
    }

    private static int rgba(int rgb, int a) {
        int aa = Math.max(0, Math.min(255, a));
        return (aa << 24) | (rgb & 0xFFFFFF);
    }
}
