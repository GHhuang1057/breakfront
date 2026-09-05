package com.breakfront.client.hud;

import com.breakfront.client.bf.BfEasing;
import com.breakfront.client.bf.BfTheme;
import com.breakfront.client.state.ClientMatchState;
import com.breakfront.client.state.ClientMatchState.ZoneView;
import com.breakfront.game.Side;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 地标（BF2042 式）v3 —— 渲染工程参照 GD656 Killicon（MIT，GitHub MinecraftGD656/GD656Killicon）：
 *
 * 进度「环」不再用 24 颗点阵刻度，改走 GD656 IconRingEffect 同款连续圆环：
 * TRIANGLE_STRIP 三角带绘制（annulus），推进弧按 meter 平滑扫过、防守安定整圈弱蓝、
 * 攻方安定整圈亮黄、争夺弧白/橙呼吸。
 *
 * 事件动效（对齐 GD656 时间线风格：ms 基准 + cubic ease-out + alpha² 衰减）：
 *   - 据点首现：300ms 菱形 1.65→1.0 缩放入位 + 淡入；
 *   - 进入争夺：白色冲击环 420ms 小扩散；
 *   - 攻占成功：黄色冲击环 650ms 大扩散；被夺回：蓝色冲击环。
 *
 * 屏缘方位箭头保留（世界内方块描边不可见时兜底指方向）。
 * 纯 2D 几何矢量绘制，无贴图。
 */
public final class ZoneMarkers {

    /** 顶栏尺寸（BreakfrontHud 共用，保证目标带与顶栏对齐）。 */
    public static final int PILL_TOP = 6;
    public static final int PILL_H = 32;

    private static final int FONT_CENTER_Y = -4;
    private static final double TAU = Math.PI * 2.0;

    /** 据点推进度平滑缓冲（zoneId -> 当前显示值 0..1）。 */
    private static final Map<String, Float> smoothMeter = new HashMap<>();
    /** 据点首现时间（入场动画）。 */
    private static final Map<String, Long> enterAt = new HashMap<>();
    /** 上一帧状态（事件检测）。 */
    private static final Map<String, LastState> lastState = new HashMap<>();
    /** 活动中的事件脉冲（同一据点同时最多一个，新事件覆盖旧事件）。 */
    private static final List<Pulse> pulses = new ArrayList<>();

    private static final int KIND_CONTEST = 0;   // 进入争夺
    private static final int KIND_CAPTURED = 1;  // 攻方占领
    private static final int KIND_RECAPT = 2;    // 防守夺回

    private static final class LastState {
        int ownerOrdinal;
        float meter;

        LastState(int ownerOrdinal, float meter) {
            this.ownerOrdinal = ownerOrdinal;
            this.meter = meter;
        }
    }

    private static final class Pulse {
        final String zoneId;
        final int kind;
        final long at;
        final int color;

        Pulse(String zoneId, int kind, long at, int color) {
            this.zoneId = zoneId;
            this.kind = kind;
            this.at = at;
            this.color = color;
        }

        /** 脉冲总时长 ms。 */
        long duration() {
            return kind == KIND_CAPTURED ? 650L : (kind == KIND_RECAPT ? 650L : 420L);
        }
    }

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
            double wy = WorldZoneRings.anchorY(zone) + 2.0;
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
     * 菱形带颜色字母 + 外侧连续圆环进度（平滑）。返回实际占用的水平宽度。
     */
    public static int renderRailInto(DrawContext ctx, TextRenderer font,
                                     int x, int y, int w, int h, List<ZoneView> zones) {
        if (zones.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        detectEvents(zones, now);
        prune(zones);

        int count = Math.min(zones.size(), 8);
        // 尺寸收紧：环外沿必须留在顶栏内、避开下方票数进度条(gy=y+pillH-4≈34)。
        // h=30 时 dia=16、tickR=10 → 环外沿 cy0+10+1.3≈33.3 < 34，不再压条。
        int dia = Math.min(16, h - 10);            // 菱形外接圆直径
        int tickR = dia / 2 + 2;                    // 进度环半径
        int total = count * (tickR * 2 + 2) - 2;
        int cx0 = x + Math.max(0, (w - total) / 2);
        int cy0 = y + h / 2;
        int used = 0;
        for (int i = 0; i < count; i++) {
            ZoneView zone = zones.get(i);
            int cx = cx0 + i * (tickR * 2 + 2);
            drawRailDiamond(ctx, font, cx, cy0, dia / 2.0, tickR, zone, now);
            used += tickR * 2 + 2;
        }
        return used;
    }

    /** 帧级事件检测：状态跃迁 → 触发冲击脉冲；补齐入场时间。 */
    private static void detectEvents(List<ZoneView> zones, long now) {
        for (ZoneView zone : zones) {
            enterAt.computeIfAbsent(zone.zoneId(), k -> now);
            Side owner = Side.values()[zone.ownerOrdinal()];
            float meter = Math.max(0f, Math.min(1f, zone.meter()));
            LastState last = lastState.get(zone.zoneId());
            if (last == null) {
                lastState.put(zone.zoneId(), new LastState(zone.ownerOrdinal(), meter));
                continue;
            }
            boolean wasAtt = last.ownerOrdinal == Side.ATTACKER.ordinal();
            boolean isAtt = owner == Side.ATTACKER;
            if (wasAtt && !isAtt) {
                // 攻方据点被防守方夺回
                triggerPulse(zone.zoneId(), KIND_RECAPT, now, 0xFF4DA6FF);
            } else if (!wasAtt && isAtt) {
                // 攻方占领成功
                triggerPulse(zone.zoneId(), KIND_CAPTURED, now, 0xFFF5D44A);
            } else if (!isAtt && last.meter < 0.03f && meter >= 0.05f) {
                // 进入争夺（防守据点开始被推进）
                triggerPulse(zone.zoneId(), KIND_CONTEST, now, 0xFFFFFFFF);
            }
            lastState.put(zone.zoneId(), new LastState(zone.ownerOrdinal(), meter));
        }
    }

    private static void triggerPulse(String zoneId, int kind, long now, int color) {
        pulses.removeIf(p -> p.zoneId.equals(zoneId));
        pulses.add(new Pulse(zoneId, kind, now, color));
    }

    private static Pulse activePulse(String zoneId, long now) {
        for (Pulse p : pulses) {
            if (p.zoneId.equals(zoneId) && now - p.at < p.duration()) {
                return p;
            }
        }
        return null;
    }

    /** 清理已不在列表的据点的缓冲/事件。 */
    private static void prune(List<ZoneView> zones) {
        java.util.Set<String> live = new java.util.HashSet<>();
        for (ZoneView z : zones) {
            live.add(z.zoneId());
        }
        lastState.keySet().removeIf(id -> !live.contains(id));
        enterAt.keySet().removeIf(id -> !live.contains(id));
        smoothMeter.keySet().removeIf(id -> !live.contains(id));
        long now = System.currentTimeMillis();
        pulses.removeIf(p -> now - p.at > p.duration());
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

        // 入场动画（菱形缩放 + 整体淡入）
        Long ent = enterAt.get(zone.zoneId());
        float eIn = (float) BfEasing.easeOutCubic(BfEasing.clamp01((now - (ent == null ? now : ent)) / 300.0));
        double sc = 1.0 + 0.40 * (1.0 - eIn);          // 1.40 → 1.0（不越出 32px 顶栏）
        int eAlpha = Math.max(1, (int) (255 * eIn));

        // 1) 事件冲击环（在最底层：先画）
        Pulse pulse = activePulse(zone.zoneId(), now);
        if (pulse != null) {
            double pt = BfEasing.clamp01((now - pulse.at) / (double) pulse.duration());
            double pEase = BfEasing.easeOutCubic(pt);
            double pR0 = tickR + 1.5;
            double pR1 = tickR + (pulse.kind == KIND_CONTEST ? 9.0 : 16.0);
            double pr = pR0 + (pR1 - pR0) * pEase;
            int pA = (int) (235 * (1.0 - pt) * (1.0 - pt));   // alpha² 衰减（GD656 式）
            double pw = Math.max(0.8, 2.8 * (1.0 - pt));
            int pCol = pulse.color;
            drawRingArc(ctx, cx, cy, pr - pw / 2, pr + pw / 2, 0, TAU,
                    (pCol >> 16) & 0xFF, (pCol >> 8) & 0xFF, pCol & 0xFF,
                    (int) (pA * (eAlpha / 255.0)));
        }

        // 2) 底影菱形 + 状态色菱形（缩放入位）
        double hs = half * sc;
        drawDiamond(ctx, cx, cy, hs + 1.4, hs + 1.4, 0, 0, 0, (int) (0xB0 * (eAlpha / 255.0)));
        int flicker = contested && (now % 500) < 250 ? 46 : 0;
        drawDiamond(ctx, cx, cy, hs, hs,
                Math.min(255, col[0] + flicker),
                Math.min(255, col[1] + flicker),
                Math.min(255, col[2] + flicker),
                Math.min(255, (int) (col[3] * (eAlpha / 255.0)) + flicker));

        // 3) 字母
        String letter = zone.letter();
        int lw = font.getWidth(letter);
        int textCol = (col[0] * 3 + col[1] * 6 + col[2]) > 1500 ? 0xFF0A0D12 : 0xFFFFFFFF;
        ctx.drawText(font, Text.literal(letter), cx - lw / 2,
                cy - font.fontHeight / 2 + FONT_CENTER_Y, textCol, false);

        // 4) 连续圆环：弱底全环 + 进度亮弧
        double rc = tickR;
        double th = 2.6;
        // 状态决定环样式
        int dimColor;
        int dimA;
        int litColor;
        double sweep;
        if (owner == Side.ATTACKER) {
            dimColor = 0xF5D44A;
            dimA = 110;
            litColor = 0xF5D44A;
            sweep = TAU;
        } else if (!contested) {
            dimColor = 0x4DA6FF;
            dimA = 70;
            litColor = 0x4DA6FF;
            sweep = 0;
        } else {
            dimColor = 0xFFFFFF;
            dimA = 60;
            litColor = (now % 700) < 350 ? 0xFFFFFF : 0xF5D44A;
            sweep = TAU * shown;
        }
        drawRingArc(ctx, cx, cy, rc - th / 2, rc + th / 2, 0, TAU,
                (dimColor >> 16) & 0xFF, (dimColor >> 8) & 0xFF, dimColor & 0xFF,
                (int) (dimA * (eAlpha / 255.0)));
        if (sweep > 0.02) {
            drawRingArc(ctx, cx, cy, rc - th / 2, rc + th / 2,
                    -Math.PI / 2, -Math.PI / 2 + sweep,
                    (litColor >> 16) & 0xFF, (litColor >> 8) & 0xFF, litColor & 0xFF,
                    (int) (235 * (eAlpha / 255.0)));
        }
    }

    // ================= 几何工具 =================

    /** 据点状态主色（2D 用）。 */
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

    /**
     * GD656 IconRingEffect.drawRing 同款连续圆环（2D GUI 三角带）。
     * 绘制 [from,to] 角度（弧度，0=右、正=顺时针）之间的圆环带，内外径等差细分。
     */
    private static void drawRingArc(DrawContext ctx, double cx, double cy,
                                    double rIn, double rOut,
                                    double from, double to,
                                    int r, int g, int b, int a) {
        if (rIn <= 0 || rOut <= rIn || a <= 0) {
            return;
        }
        double span = to - from;
        if (span <= 0) {
            return;
        }
        int segs = (int) Math.max(4, Math.min(96, Math.ceil(Math.abs(span) / TAU * 56)));
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        Matrix4f m = ctx.getMatrices().peek().getPositionMatrix();
        float fr = r / 255f;
        float fg = g / 255f;
        float fb = b / 255f;
        float fa = Math.min(1f, a / 255f);
        BufferBuilder buf = Tessellator.getInstance()
                .begin(VertexFormat.DrawMode.TRIANGLE_STRIP, VertexFormats.POSITION_COLOR);
        for (int i = 0; i <= segs; i++) {
            double ang = from + span * i / segs;
            double cos = Math.cos(ang);
            double sin = Math.sin(ang);
            buf.vertex(m, (float) (cx + cos * rOut), (float) (cy + sin * rOut), 0)
                    .color(fr, fg, fb, fa);
            buf.vertex(m, (float) (cx + cos * rIn), (float) (cy + sin * rIn), 0)
                    .color(fr, fg, fb, fa);
        }
        BufferRenderer.drawWithGlobalProgram(buf.end());
        RenderSystem.disableBlend();
    }
}
