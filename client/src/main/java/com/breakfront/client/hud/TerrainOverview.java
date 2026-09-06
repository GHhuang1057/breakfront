package com.breakfront.client.hud;

import com.breakfront.client.bf.BfDraw;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.Heightmap;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 部署屏「真实地形俯瞰」静态工具（纯客户端、无服务器新协议）。
 *
 * <p>职责：把一个屏幕矩形（地图面板）对应的世界矩形采样成低分辨率高度矩阵，
 * 归一化后绘制成 2D 矢量俯瞰底图（每个采样点一个色块，高度→深浅蓝绿渐变，
 * 水面/低地→深蓝）。上层拓扑（据点、可部署点、玩家菱形、出生点）由
 * {@code BfDeployScreen} 在其上叠加。
 *
 * <p>开销控制：
 * <ul>
 *   <li>采样粒度 {@link #SAMPLE_STEP_M}=4（米/格）。矩阵尺寸自动取
 *       {@code cols = clamp( ceil(worldW/step), 1, maxCells )} 且再受屏幕像素约束
 *       {@code cols <= screenW/2}（保证每格≥2px）。默认 maxCells=90 →
 *       最多 90×90=8100 次采样 / 每帧 ≤8100 次 fill。</li>
 *   <li>区块级 LRU 缓存：键=区块坐标 (cx,cz)，值=该 16×16 列高度数组。
 *       同一区块列只在首次 miss 时调用一次 {@code getTopY}，之后全为数组读。</li>
 *   <li>未加载区块返回 {@link #UNSET}，绘制为「无数据」深色，区块载入后自然补齐。</li>
 *   <li>高度可低至 -60 以下（Metro 地下地图），故用视口内 min/max 相对归一化，
 *       海平面以下按深水处理。</li>
 * </ul>
 *
 * <p>生命周期：仅 {@code BfDeployScreen} 的渲染期调用 {@link #draw}。世界切换或
 * 切屏时调用 {@link #release()} 释放引用（静态 map 另有硬上限 + LRU 兜底，不会泄漏）。
 */
public final class TerrainOverview {

    private TerrainOverview() {
    }

    // ---- 调参常量 ----
    /** 期望采样粒度（米/格）。 */
    public static final int SAMPLE_STEP_M = 4;
    /** 矩阵单维硬上限（cols/rows 各自 ≤ 此值）。 */
    public static final int DEFAULT_MAX_CELLS = 90;
    /** 海平面（overworld ≈ 63，取 62）：此高度及以下视为水面/深渊 → 深蓝。 */
    public static final int SEA_LEVEL = 62;
    /** 区块缓存上限（LRU）。约 1500×256 int ≈ 1.5MB。 */
    public static final int CHUNK_CACHE_CAP = 1500;
    /** 未加载/无数据哨兵。 */
    public static final int UNSET = Integer.MIN_VALUE;

    /** 深度/高度低色（午夜青）。 */
    private static final int LOW_R = 10, LOW_G = 30, LOW_B = 42;
    /** 高度高色（亮青绿）。 */
    private static final int HIGH_R = 150, HIGH_G = 240, HIGH_B = 216;
    /** 水面色（深蓝）。 */
    private static final int WATER_R = 8, WATER_G = 44, WATER_B = 74;
    /** 无数据色（面板底）。 */
    private static final int NODATA = 0xFF0C1622;

    /** 区块坐标 → 16×16 列顶高（局部索引 lx*16+lz，UNSET=无数据）。LRU + 硬上限。 */
    private static final LinkedHashMap<Long, int[]> CHUNK_CACHE =
            new LinkedHashMap<Long, int[]>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, int[]> eldest) {
                    return size() > CHUNK_CACHE_CAP;
                }
            };

    /** 绑定的世界；世界切换时清空缓存避免跨维度串数据。 */
    private static ClientWorld boundWorld = null;

    /** 诊断：上次 draw 中实际触发 getTopY 的次数（缓存命中不计）。供帧率监控采样。 */
    public static int lastWorldSamples = 0;

    // ============================================================
    // 坐标换算（供 BfDeployScreen 复用，避免重复实现）
    // 约定：screenX = offX + worldX * scale（与 BfDeployScreen 现有映射一致）
    // ============================================================

    public static int worldToScreenX(double offX, double scale, double worldX) {
        return (int) (offX + worldX * scale);
    }

    public static int worldToScreenZ(double offY, double scale, double worldZ) {
        return (int) (offY + worldZ * scale);
    }

    public static double screenToWorldX(double offX, double scale, int screenX) {
        return (screenX - offX) / scale;
    }

    public static double screenToWorldZ(double offY, double scale, int screenY) {
        return (screenY - offY) / scale;
    }

    // ============================================================
    // 绘制
    // ============================================================

    /**
     * 在屏幕矩形 (sx,sy,sw,sh) 内绘制地形俯瞰底图。
     *
     * @param world  客户端世界（null 时直接返回，不绘制）
     * @param sx,sy  面板屏幕左上角
     * @param sw,sh  面板像素尺寸
     * @param offX   世界→屏幕平移（screenX = offX + worldX*scale）
     * @param offY   世界→屏幕平移（screenY = offY + worldZ*scale）
     * @param scale  世界→屏幕缩放（像素/米）
     * @param maxCells 矩阵单维上限（建议 {@link #DEFAULT_MAX_CELLS}）
     */
    public static void draw(DrawContext ctx, ClientWorld world,
                            int sx, int sy, int sw, int sh,
                            double offX, double offY, double scale, int maxCells) {
        if (world == null || sw <= 0 || sh <= 0) {
            return;
        }
        if (world != boundWorld) { // 世界切换 → 清缓存
            CHUNK_CACHE.clear();
            boundWorld = world;
        }
        lastWorldSamples = 0;

        // 视口对应的世界矩形（由当前 off/scale 反推，确保与上层拓扑严格对齐）
        double worldMinX = screenToWorldX(offX, scale, sx);
        double worldMaxX = screenToWorldX(offX, scale, sx + sw);
        double worldMinZ = screenToWorldZ(offY, scale, sy);
        double worldMaxZ = screenToWorldZ(offY, scale, sy + sh);
        double worldW = worldMaxX - worldMinX;
        double worldH = worldMaxZ - worldMinZ;
        if (worldW <= 0 || worldH <= 0) {
            return;
        }

        // 矩阵尺寸：4m 粒度，但受 maxCells 与「每格≥2px」双重约束
        int cols = (int) Math.ceil(worldW / SAMPLE_STEP_M);
        int rows = (int) Math.ceil(worldH / SAMPLE_STEP_M);
        cols = clamp(cols, 1, Math.min(maxCells, Math.max(1, sw / 2)));
        rows = clamp(rows, 1, Math.min(maxCells, Math.max(1, sh / 2)));
        double stepX = worldW / cols;
        double stepZ = worldH / rows;

        // 采样高度矩阵
        int[] hm = new int[cols * rows];
        int minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE;
        for (int r = 0; r < rows; r++) {
            double wz = worldMinZ + (r + 0.5) * stepZ;
            for (int c = 0; c < cols; c++) {
                double wx = worldMinX + (c + 0.5) * stepX;
                int hy = sample(world, wx, wz);
                hm[r * cols + c] = hy;
                if (hy != UNSET) {
                    if (hy < minH) minH = hy;
                    if (hy > maxH) maxH = hy;
                }
            }
        }
        int range = Math.max(1, maxH - minH);

        // 绘制每个采样点为色块（按屏幕矩形精确铺满，+1 消缝）
        for (int r = 0; r < rows; r++) {
            int top = (int) (offY + (worldMinZ + r * stepZ) * scale);
            int bot = (int) (offY + (worldMinZ + (r + 1) * stepZ) * scale);
            for (int c = 0; c < cols; c++) {
                int hy = hm[r * cols + c];
                int color;
                if (hy == UNSET) {
                    color = NODATA;
                } else {
                    float t = (hy - minH) / (float) range; // 0..1 相对高度
                    float shade = hillshade(hm, cols, rows, r, c, hy); // 0.6..1.18 山体阴影
                    if (hy <= SEA_LEVEL) {
                        color = pack(WATER_R * shade, WATER_G * shade, WATER_B * shade);
                    } else {
                        int rr = lerp(LOW_R, HIGH_R, t);
                        int gg = lerp(LOW_G, HIGH_G, t);
                        int bb = lerp(LOW_B, HIGH_B, t);
                        color = pack(rr * shade, gg * shade, bb * shade);
                    }
                }
                int left = (int) (offX + (worldMinX + c * stepX) * scale);
                int right = (int) (offX + (worldMinX + (c + 1) * stepX) * scale);
                ctx.fill(left, top, right + 1, bot + 1, color);
            }
        }

        // 轻微暗角，压住边缘避免与面板描边打架
        BfDraw.border(ctx, sx, sy, sw, sh, 0x20_000000);
    }

    /** 释放缓存引用（切屏 / LOBBY / 死亡时调用，避免泄漏）。世界切换亦会自动清空。 */
    public static void release() {
        CHUNK_CACHE.clear();
        boundWorld = null;
        lastWorldSamples = 0;
    }

    // ============================================================
    // 采样（带区块 LRU 缓存）
    // ============================================================

    /** 取世界坐标处的地表顶 Y；区块未加载返回 UNSET。结果按区块列缓存。 */
    private static int sample(ClientWorld world, double x, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int cx = bx >> 4;
        int cz = bz >> 4;
        int lx = bx & 15;
        int lz = bz & 15;
        long key = chunkKey(cx, cz);

        int[] colsArr = CHUNK_CACHE.get(key);
        if (colsArr == null) {
            if (!world.getChunkManager().isChunkLoaded(cx, cz)) {
                return UNSET;
            }
            colsArr = new int[256];
            java.util.Arrays.fill(colsArr, UNSET);
            CHUNK_CACHE.put(key, colsArr);
        }
        int idx = lx * 16 + lz;
        int cached = colsArr[idx];
        if (cached != UNSET) {
            return cached;
        }
        // miss：仅在区块已加载时采样，否则保持 UNSET（等区块到位后补）
        if (!world.getChunkManager().isChunkLoaded(cx, cz)) {
            return UNSET;
        }
        int top;
        try {
            top = world.getTopY(Heightmap.Type.MOTION_BLOCKING, bx, bz);
        } catch (RuntimeException e) {
            // 区块在检查与采样间隙卸载等极端情况：留作无数据，下帧再试
            return UNSET;
        }
        colsArr[idx] = top;
        lastWorldSamples++;
        return top;
    }

    // ---- 小工具 ----

    private static long chunkKey(int cx, int cz) {
        return ((long) cx & 0xFFFFFFFFL) | (((long) cz & 0xFFFFFFFFL) << 32);
    }

    /** 邻接高度差 → 山体阴影系数（NW 向光）。 */
    private static float hillshade(int[] hm, int cols, int rows, int r, int c, int hy) {
        int hl = c > 0 ? hm[r * cols + (c - 1)] : hy;
        int hr = c < cols - 1 ? hm[r * cols + (c + 1)] : hy;
        int hu = r > 0 ? hm[(r - 1) * cols + c] : hy;
        int hd = r < rows - 1 ? hm[(r + 1) * cols + c] : hy;
        if (hl == UNSET) hl = hy;
        if (hr == UNSET) hr = hy;
        if (hu == UNSET) hu = hy;
        if (hd == UNSET) hd = hy;
        float gx = hl - hr;
        float gz = hu - hd;
        float shade = 1f - (gx + gz) * 0.012f;
        return clamp(shade, 0.6f, 1.18f);
    }

    private static int lerp(int a, int b, float t) {
        return (int) (a + (b - a) * clamp(t, 0f, 1f));
    }

    private static int pack(float r, float g, float b) {
        int rr = clamp((int) r, 0, 255);
        int gg = clamp((int) g, 0, 255);
        int bb = clamp((int) b, 0, 255);
        return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
