package com.breakfront.client.hud;

import com.breakfront.client.bf.BfDraw;
import com.breakfront.client.bf.BfGlow;
import com.breakfront.client.bf.BfTheme;
import com.breakfront.client.state.ClientMatchState;
import com.breakfront.client.state.ClientMatchState.FriendDot;
import com.breakfront.client.state.ClientMatchState.ZoneView;
import com.breakfront.game.Side;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.List;

/**
 * BF 式小地图（矢量，方形，左下角竖向堆叠于血量卡下方）：
 * - 自机三角箭头固定指上（画面随朝向旋转）
 * - 地形底：真实方块色淡显（6m 格，区块缓存 2s 刷新；未加载格透明不画黑）
 * - 据点：菱形（owner 色：攻 GREEN / 守 BLUE / 争夺 CYAN 白闪）
 * - 友军：同阵营青色菱形，阵亡灰显
 * - 世界范围 RANGE=90 米，映射到边长 size 的方形（N 在上）
 * 渲染纪律：几何 + 文字，无贴图。
 */
public final class BfMinimap {

    private static final double RANGE = 90.0;
    /** 地形底采样格（米）——屏内 180m / 6m = 30 列。 */
    private static final int STEP = 6;
    private static final int CELLS = 34;                 // ±(RANGE+step) 覆盖
    private static final int C_OFF = CELLS / 2;
    /** 地形底缓存（argb，0=未采样/未加载）。按玩家所在格刷新。 */
    private static final int[] TERRAIN = new int[CELLS * CELLS];
    private static int tCellX = Integer.MAX_VALUE;
    private static int tCellZ = Integer.MAX_VALUE;
    private static long tMs;
    private static ClientWorld tWorld;

    private BfMinimap() {
    }

    /** 地形底绘制（方块色 ×0.62 低显叠 PANEL 上，未加载格透明）。 */
    private static void drawTerrain(DrawContext ctx, MinecraftClient client,
                                    int bx, int by, int pad, int cx, int cy,
                                    double px, double pz, double cos, double sin,
                                    double scale, int size, int half) {
        ClientWorld world = client.world;
        if (world == null) {
            return;
        }
        refreshTerrain(world, px, pz);
        double halfSpan = (half - pad) / scale; // 可视世界半径（米）
        int x0 = Math.max(0, (int) Math.floor(-halfSpan / STEP) + C_OFF);
        int x1 = Math.min(CELLS - 1, (int) Math.ceil(halfSpan / STEP) + C_OFF);
        int z0 = Math.max(0, (int) Math.floor(-halfSpan / STEP) + C_OFF);
        int z1 = Math.min(CELLS - 1, (int) Math.ceil(halfSpan / STEP) + C_OFF);
        int cellPx = Math.max(2, (int) Math.ceil(STEP * scale));
        for (int iz = z0; iz <= z1; iz++) {
            for (int ix = x0; ix <= x1; ix++) {
                int argb = TERRAIN[iz * CELLS + ix];
                if (argb == 0) {
                    continue;
                }
                double wx = (tCellX + (ix - C_OFF)) * STEP;
                double wz = (tCellZ + (iz - C_OFF)) * STEP;
                double ox = wx - px;
                double oz = wz - pz;
                double sx = cx + (ox * cos + oz * sin) * scale;
                double sy = cy + (ox * sin - oz * cos) * scale;
                ctx.fill((int) sx, (int) sy,
                        (int) sx + cellPx, (int) sy + cellPx, argb);
            }
        }
    }

    /** 玩家移动 ≥1 格或每 2s 刷新地形矩阵（仅读 heightmap + 方块 id，开销可控）。 */
    private static void refreshTerrain(ClientWorld world, double px, double pz) {
        int cX = (int) Math.floor(px / STEP);
        int cZ = (int) Math.floor(pz / STEP);
        long now = System.currentTimeMillis();
        if (tWorld == world && tCellX == cX && tCellZ == cZ && now - tMs < 2000) {
            return;
        }
        tWorld = world;
        tCellX = cX;
        tCellZ = cZ;
        tMs = now;
        for (int iz = 0; iz < CELLS; iz++) {
            for (int ix = 0; ix < CELLS; ix++) {
                TERRAIN[iz * CELLS + ix] = cellColor(world,
                        (cX + ix - C_OFF) * STEP + 0.5, (cZ + iz - C_OFF) * STEP + 0.5);
            }
        }
    }

    /** 世界坐标采样：未加载区块/无方块列返回 0（不画）；否则低显方块色。 */
    private static int cellColor(ClientWorld world, double x, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        if (!world.getChunkManager().isChunkLoaded(bx >> 4, bz >> 4)) {
            return 0;
        }
        int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING, bx, bz);
        if (top <= world.getBottomY()) {
            return 0;
        }
        BlockState bs = world.getBlockState(new BlockPos(bx, top, bz));
        int rgb = paletteOf(bs);
        // 低显淡入：alpha ~0x62、色乘 0.6 并偏冷，保持与 PANEL 底和谐
        int r = (rgb >> 16 & 0xFF) * 62 / 100;
        int g = (rgb >> 8 & 0xFF) * 64 / 100;
        int b = (rgb & 0xFF) * 72 / 100;
        return 0x62000000 | (r << 16) | (g << 8) | b;
    }

    /** 材质关键词 → 低饱和蓝绿系色块（未匹配给建筑青灰）。 */
    private static int paletteOf(BlockState bs) {
        String id = Registries.BLOCK.getId(bs.getBlock()).getPath();
        if (id.contains("water")) {
            return 0x3A74B4;
        }
        if (id.contains("lava")) {
            return 0xB05328;
        }
        if (id.contains("sand")) {
            return 0xBFB68C;
        }
        if (id.contains("grass") || id.contains("moss") || id.contains("mycel")) {
            return 0x6F9E68;
        }
        if (id.contains("snow") || id.contains("powder")) {
            return 0xC9D2DC;
        }
        if (id.contains("deepslate") || id.contains("blackstone")) {
            return 0x4E525C;
        }
        if (id.contains("stone") || id.contains("tuff") || id.contains("calcite")
                || id.contains("granite") || id.contains("diorite") || id.contains("andesite")) {
            return 0x8A8F9A;
        }
        if (id.contains("cobble") || id.contains("gravel")) {
            return 0x7A7E8A;
        }
        if (id.contains("brick") || id.contains("terracotta")
                || id.contains("red_sandstone")) {
            return 0xA2645A;
        }
        if (id.contains("plank") || id.contains("log") || id.contains("wood")) {
            return 0x92764F;
        }
        if (id.contains("leaves")) {
            return 0x4A7A4E;
        }
        if (id.contains("concrete")) {
            return 0xA8AFBA;
        }
        if (id.contains("glass") || id.contains("ice")) {
            return 0x86AFC8;
        }
        if (id.contains("rail") || id.contains("copper")) {
            return 0x8F6A48;
        }
        if (id.contains("iron") || id.contains("gold") || id.contains("coal")
                || id.contains("ore")) {
            return 0x7A7E8A;
        }
        if (id.contains("clay")) {
            return 0x9AA3AC;
        }
        if (id.contains("obsidian")) {
            return 0x34363E;
        }
        return 0x55697E; // 建筑/道路默认：冷青灰
    }

    /**
     * @param x     左上角 x（已含视差偏移由调用方决定）
     * @param y     左上角 y
     * @param size  方形边长
     * @param bobDx 视差横向（px，叠加到 x）
     * @param bobDy 视差纵向（px，叠加到 y）
     */
    public static void render(DrawContext ctx, TextRenderer font, int x, int y, int size,
                              double bobDx, double bobDy) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return;
        }
        int bx = x + (int) Math.round(bobDx);
        int by = y + (int) Math.round(bobDy);
        int half = size / 2;
        int cx = bx + half;
        int cy = by + half;
        int pad = Math.max(4, size / 28);

        // 底盘：PANEL 方形 + 青色线框 + 内描边 + 微量辉光
        BfDraw.fill(ctx, bx, by, size, size, BfTheme.PANEL);
        BfDraw.border(ctx, bx, by, size, size, BfTheme.CYAN_DIM);
        BfDraw.fill(ctx, bx + 1, by + 1, size - 2, 1, BfTheme.PANEL_LINE);
        BfGlow.rect(ctx, bx, by, size, size, BfTheme.TEAL & 0xFFFFFF, 18, 4);

        double px = client.player.getX();
        double pz = client.player.getZ();
        float yaw = client.player.getYaw();
        double rad = Math.toRadians(yaw);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double scale = (double) (half - pad) / RANGE;

        // 真实地形底（方块色淡显；区块未加载保持底盘暗青底，绝不画黑）
        drawTerrain(ctx, client, bx, by, pad, cx, cy, px, pz, cos, sin, scale, size, half);

        // 网格参考线（暗）
        ctx.fill(cx - 1, by + pad, cx + 1, by + size - pad, 0x16FFFFFF);
        ctx.fill(bx + pad, cy - 1, bx + size - pad, cy + 1, 0x16FFFFFF);

        // 我方阵营
        String me = client.player.getName().getString();
        int mySide = -1;
        for (FriendDot f : ClientMatchState.friends()) {
            if (f.name().equals(me)) {
                mySide = f.sideOrdinal();
                break;
            }
        }

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
                    ? ((now % 600) < 300 ? 0xFFEFFFFF : BfTheme.CYAN)
                    : (owner == Side.ATTACKER ? BfTheme.GREEN : BfTheme.BLUE);
            double r = Math.max(3.0, Math.min(half - 4, z.radius() * 0.35 * scale + 3));
            BfDraw.diamond(ctx, sx, sy, r, edge);
            // 字母（深色描底保证可读）
            String letter = z.letter();
            int lw = font.getWidth(letter);
            ctx.drawText(font, Text.literal(letter), (int) sx - lw / 2, (int) sy - font.fontHeight / 2,
                    0xFF0A0D12, false);
            ctx.drawText(font, Text.literal(letter), (int) sx - lw / 2 - 1, (int) sy - font.fontHeight / 2 - 1,
                    BfTheme.TEXT, false);
        }

        // 友军点（青色菱形，阵亡灰）
        int ally = BfTheme.CYAN;
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
            BfDraw.diamond(ctx, sx, sy, 3.0, col);
        }

        // 自机三角箭头（固定朝上，白/青）
        int[] ax = {cx, cx - 5, cx + 5};
        int[] ay = {cy - 8, cy + 5, cy + 5};
        BfDraw.fill(ctx, cx - 1, cy - 7, cx + 1, cy + 5, 0xFF0A0D12);
        tri(ctx, ax, ay, 0xFFFFFFFF);

        // N 指示（屏幕上方 = 北）
        String n = "N";
        int nw = font.getWidth(n);
        ctx.drawText(font, Text.literal(n), cx - nw / 2, by + 2, BfTheme.CYAN, true);
    }

    /** 扫描线三角形填充（上顶点）。 */
    private static void tri(DrawContext ctx, int[] xs, int[] ys, int color) {
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
