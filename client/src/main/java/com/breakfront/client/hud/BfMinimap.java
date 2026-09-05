package com.breakfront.client.hud;

import com.breakfront.client.bf.BfDraw;
import com.breakfront.client.bf.BfTheme;
import com.breakfront.client.state.ClientMatchState;
import com.breakfront.client.state.ClientMatchState.FriendDot;
import com.breakfront.client.state.ClientMatchState.ZoneView;
import com.breakfront.game.Side;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;

/**
 * BF 式雷达（矢量，右上角）：
 * - 自机箭头固定指上（画面随朝向旋转）
 * - 据点：按扇区字母的菱形/圆点 + 争夺白闪
 * - 友军：同阵营点（攻方黄 / 守方蓝），阵亡灰显
 * - 世界范围 RANGE=90 米，屏幕半径 46px
 * 渲染纪律：几何 + 文字，无贴图。
 */
public final class BfMinimap {

    private static final double RANGE = 90.0;
    private static final int RADIUS = 46;

    private BfMinimap() {
    }

    public static void render(DrawContext ctx, TextRenderer font, int sw, int sh) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return;
        }
        int cx = sw - RADIUS - 14;
        int cy = 12 + RADIUS;

        // 底盘：外圈描边 + 深色盘面
        disc(ctx, cx, cy, RADIUS, 0xFF1E2733);
        disc(ctx, cx, cy, RADIUS - 2, 0xE610141B);
        // 水平/垂直参考线（暗）
        ctx.fill(cx - RADIUS + 2, cy - 1, cx + RADIUS - 2, cy + 1, 0x18FFFFFF);
        ctx.fill(cx - 1, cy - RADIUS + 2, cx + 1, cy + RADIUS - 2, 0x18FFFFFF);

        double px = client.player.getX();
        double pz = client.player.getZ();
        float yaw = client.player.getYaw();
        double rad = Math.toRadians(yaw);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double scale = (double) (RADIUS - 6) / RANGE;

        // 我方阵营：从位置帧里找到自己
        String me = client.player.getName().getString();
        int mySide = -1;
        for (FriendDot f : ClientMatchState.friends()) {
            if (f.name().equals(me)) {
                mySide = f.sideOrdinal();
                break;
            }
        }

        // 据点（全部阵营可见——BF 里目标是公共情报）
        long now = System.currentTimeMillis();
        List<ZoneView> zones = ClientMatchState.zones();
        for (ZoneView z : zones) {
            double ox = z.worldX() - px;
            double oz = z.worldZ() - pz;
            double dist = Math.hypot(ox, oz);
            if (dist > RANGE || dist < 0.001) {
                continue;
            }
            double sx = cx + (ox * cos + oz * sin) * scale;
            double sy = cy + (ox * sin - oz * cos) * scale;
            Side owner = Side.values()[z.ownerOrdinal()];
            boolean contested = owner == Side.DEFENDER && z.meter() > 1e-3f;
            int edge = contested
                    ? ((now % 600) < 300 ? 0xFFFFFFFF : BfTheme.YELLOW)
                    : (owner == Side.ATTACKER ? BfTheme.YELLOW : BfTheme.BLUE);
            int r = Math.max(4, (int) Math.round(Math.max(2.0, z.radius() * 0.35)));
            disc(ctx, (int) sx, (int) sy, r, 0x40000000);
            disc(ctx, (int) sx, (int) sy, Math.max(2, r - 1), edge);
            // 字母（黑色描底保证可读）
            String letter = z.letter();
            int lw = font.getWidth(letter);
            ctx.drawText(font, Text.literal(letter), (int) sx - lw / 2, (int) sy - 4,
                    0xFF0A0D12, false);
            ctx.drawText(font, Text.literal(letter), (int) sx - lw / 2 - 1, (int) sy - 5,
                    0xFFFFFFFF, false);
        }

        // 友军点
        int ally = mySide == 0 ? BfTheme.YELLOW : BfTheme.BLUE;
        for (FriendDot f : ClientMatchState.friends()) {
            if (f.sideOrdinal() != mySide || f.sideOrdinal() < 0) {
                continue;
            }
            double ox = f.x() - px;
            double oz = f.z() - pz;
            double dist = Math.hypot(ox, oz);
            if (dist > RANGE || dist < 0.001) {
                continue;
            }
            double sx = cx + (ox * cos + oz * sin) * scale;
            double sy = cy + (ox * sin - oz * cos) * scale;
            int col = f.alive() ? ally : 0xFF6B7280;
            BfDraw.diamond(ctx, sx, sy, 3.2, col);
        }

        // 自机箭头（固定朝上）
        int[] ax = {cx, cx - 5, cx + 5};
        int[] ay = {cy - 9, cy + 5, cy + 5};
        BfDraw.fill(ctx, cx - 1, cy - 8, cx + 1, cy + 5, 0xFF0A0D12);
        tri(ctx, ax, ay, 0xFFFFFFFF);
    }

    /** 简易填充圆（水平扫描行画线）。 */
    private static void disc(DrawContext ctx, int cx, int cy, int radius, int color) {
        int r2 = radius * radius;
        for (int dy = -radius; dy <= radius; dy++) {
            int half = (int) Math.sqrt(Math.max(0, r2 - dy * dy));
            ctx.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
        }
    }

    private static void tri(DrawContext ctx, int[] xs, int[] ys, int color) {
        // 扫描线三角形填充（上顶点)
        for (int i = 0; i < 3; i++) {
            int j = (i + 1) % 3;
            line(ctx, xs[i], ys[i], xs[j], ys[j], color);
        }
        int minX = Math.min(Math.min(xs[0], xs[1]), xs[2]);
        int maxX = Math.max(Math.max(xs[0], xs[1]), xs[2]);
        int minY = Math.min(Math.min(ys[0], ys[1]), ys[2]);
        int maxY = Math.max(Math.max(ys[0], ys[1]), ys[2]);
        for (int y = minY + 1; y < maxY; y++) {
            int x0 = maxX;
            int x1 = minX;
            for (int x = minX; x <= maxX; x++) {
                if (inside(xs, ys, x, y)) {
                    x0 = Math.min(x0, x);
                    x1 = Math.max(x1, x);
                }
            }
            if (x1 >= x0) {
                ctx.fill(x0, y, x1 + 1, y + 1, color);
            }
        }
    }

    private static void line(DrawContext ctx, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        while (true) {
            ctx.fill(x0, y0, x0 + 1, y0 + 1, color);
            if (x0 == x1 && y0 == y1) {
                break;
            }
            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x0 += sx;
            }
            if (e2 < dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    private static boolean inside(int[] xs, int[] ys, int x, int y) {
        boolean c = false;
        int n = xs.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            boolean cond = (ys[i] > y) != (ys[j] > y);
            if (cond && x < (xs[j] - xs[i]) * (y - ys[i]) / (double) (ys[j] - ys[i]) + xs[i]) {
                c = !c;
            }
        }
        return c;
    }
}
