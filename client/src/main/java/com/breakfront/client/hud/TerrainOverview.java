package com.breakfront.client.hud;

import com.breakfront.client.bf.BfDraw;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 部署屏「真实地形俯瞰」静态工具（纯客户端、无服务器新协议）。
 *
 * <p>职责：把一个屏幕矩形（地图面板）对应的世界矩形采样成低分辨率矩阵，
 * 用「方块真实色」+ 邻域 hillshade 受光 + 低海拔蓝绿冷光绘制成 2D 矢量俯瞰底图；
 * 上层拓扑（据点、可部署点、玩家菱形、出生点）由 {@code BfDeployScreen} 在其上叠加。
 *
 * <p>开销控制：
 * <ul>
 *   <li>采样粒度 {@link #SAMPLE_STEP_M}=4（米/格）。矩阵尺寸自动取
 *       {@code cols = clamp( ceil(worldW/step), 1, maxCells )} 且再受屏幕像素约束
 *       {@code cols <= screenW/2}（保证每格≥2px）。默认 maxCells=90 →
 *       最多 90×90=8100 次采样。</li>
 *   <li>区块级 LRU 缓存：键=区块坐标 (cx,cz)，值=该 16×16 列「双顶高」打包数组。
 *       同一区块列只在首次 miss 时调用 {@code getTopY}（×2），之后全为数组读。</li>
 *   <li>每列缓存两个顶高：MOTION_BLOCKING（地表实体顶）与 WORLD_SURFACE（含液体/雪的
 *       真实表层）——后者用于把水/岩浆/雪渲染成其自身材质色而非下方地板色。</li>
 *   <li>未加载区块返回 {@link #UNSET}，绘制为「无数据」深色，区块载入后自然补齐。</li>
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
    /** 海平面（overworld ≈ 63，取 62）：此高度及以下按深水处理（仅作范围归一兜底）。 */
    public static final int SEA_LEVEL = 62;
    /** 区块缓存上限（LRU）。约 1500×256 int ≈ 1.5MB。 */
    public static final int CHUNK_CACHE_CAP = 1500;
    /** 未加载/无数据哨兵。 */
    public static final int UNSET = Integer.MIN_VALUE;

    /** 低海拔冷光（蓝绿科幻氛围），叠在真实色上，海拔越低叠得越多。 */
    private static final int COOL_R = 70, COOL_G = 178, COOL_B = 205;
    /** 无数据色（面板底）。 */
    private static final int NODATA = 0xFF0C1622;

    /** 区块坐标 → 16×16 列「双顶高」打包数组（high16=MOTION_BLOCKING 顶，low16=WORLD_SURFACE 顶）。LRU + 硬上限。 */
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
     * 在屏幕矩形 (sx,sy,sw,sh) 内绘制地形俯瞰底图（真实材质色 + 受光 + 冷光）。
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

        // 采样「双顶高」矩阵 + 同时取真实表面色（避免每帧重复 getBlockState 开销外的重复定位）
        int[] hm = new int[cols * rows];      // 打包双顶高
        int[] colArr = new int[cols * rows];  // 0xFFrrggbb 真实色
        int minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE;
        for (int r = 0; r < rows; r++) {
            double wz = worldMinZ + (r + 0.5) * stepZ;
            for (int c = 0; c < cols; c++) {
                double wx = worldMinX + (c + 0.5) * stepX;
                int packed = sample(world, wx, wz);
                int idx = r * cols + c;
                hm[idx] = packed;
                if (packed == UNSET) {
                    colArr[idx] = NODATA;
                    continue;
                }
                int motion = decodeMotion(packed);
                int surface = decodeSurface(packed);
                if (motion < minH) minH = motion;
                if (motion > maxH) maxH = motion;
                int bx = (int) Math.floor(wx);
                int bz = (int) Math.floor(wz);
                // 真实可见表层：液体/雪覆盖时用 WORLD_SURFACE 顶，否则用 MOTION_BLOCKING 顶
                int topY = surface > motion ? surface : motion;
                BlockPos p = new BlockPos(bx, topY, bz);
                colArr[idx] = blockColor(world, p, world.getBlockState(p));
            }
        }
        int range = Math.max(1, maxH - minH);

        // 逐格绘制：真实色 → 低海拔冷光 → 邻域受光（0.75~1.15）→ 铺满屏矩形(+1 消缝)
        for (int r = 0; r < rows; r++) {
            int top = (int) (offY + (worldMinZ + r * stepZ) * scale);
            int bot = (int) (offY + (worldMinZ + (r + 1) * stepZ) * scale);
            for (int c = 0; c < cols; c++) {
                int packed = hm[r * cols + c];
                int color;
                if (packed == UNSET) {
                    color = NODATA;
                } else {
                    int motion = decodeMotion(packed);
                    float t = (motion - minH) / (float) range; // 0..1 相对高度
                    float shade = hillshade(hm, cols, rows, r, c); // 0.75..1.15 山体阴影
                    int base = colArr[r * cols + c];
                    int rr = (base >> 16) & 0xFF;
                    int gg = (base >> 8) & 0xFF;
                    int bb = base & 0xFF;
                    // 低海拔叠蓝绿冷光（海拔越低叠得越浓）
                    float cool = 0.05f + (1f - t) * 0.16f;
                    rr = lerp(rr, COOL_R, cool);
                    gg = lerp(gg, COOL_G, cool);
                    bb = lerp(bb, COOL_B, cool);
                    // 受光（NW 向光，立体感）
                    rr = clamp((int) (rr * shade), 0, 255);
                    gg = clamp((int) (gg * shade), 0, 255);
                    bb = clamp((int) (bb * shade), 0, 255);
                    color = pack(rr, gg, bb);
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
    // 采样（带区块 LRU 缓存，双顶高打包）
    // ============================================================

    /**
     * 取世界坐标处「双顶高」并打包：high16=MOTION_BLOCKING 顶，low16=WORLD_SURFACE 顶。
     * 区块未加载返回 {@link #UNSET}。结果按区块列缓存。
     */
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
            Arrays.fill(colsArr, UNSET);
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
        int motion, surface;
        try {
            motion = world.getTopY(Heightmap.Type.MOTION_BLOCKING, bx, bz);
            surface = world.getTopY(Heightmap.Type.WORLD_SURFACE, bx, bz);
        } catch (RuntimeException e) {
            // 区块在检查与采样间隙卸载等极端情况：留作无数据，下帧再试
            return UNSET;
        }
        int packed = packTop(motion, surface);
        colsArr[idx] = packed;
        lastWorldSamples++;
        return packed;
    }

    // ============================================================
    // 真实方块色映射
    // ============================================================

    /** 真实材质色：草/泥/沙/石/圆石/深板岩/砖/混凝土(多色)/木板/树叶/玻璃/水/冰/雪/铁轨… */
    private static int blockColor(ClientWorld world, BlockPos pos, BlockState state) {
        String id = Registries.BLOCK.getId(state.getBlock()).getPath();
        int c = colorByPath(id);
        if (c != 0) {
            return c;
        }
        // 兜底：按 mapColor 取色（无关键词命中时）
        try {
            MapColor mc = state.getMapColor(world, pos);
            if (mc != null) {
                return 0xFF000000 | (mc.color & 0xFFFFFF);
            }
        } catch (RuntimeException ignored) {
            // getMapColor 偶发 NPE（特殊方块），忽略 → 用默认冷灰
        }
        return rgb(90, 107, 122);
    }

    /** 按注册表 id path 关键词匹配真实色；无命中返回 0（交给 mapColor 兜底）。 */
    private static int colorByPath(String id) {
        // ---- 液体 / 冰 / 雪 / 玻璃（最优先，最易被误判）----
        if (id.contains("water")) return rgb(38, 92, 170);
        if (id.contains("lava") || id.contains("magma")) return rgb(200, 82, 26);
        if (id.contains("ice") || id.contains("frost")) return rgb(150, 206, 236);
        if (id.contains("snow") || id.contains("powder")) return rgb(226, 233, 241);
        if (id.contains("glass")) return rgb(150, 202, 222);
        // ---- 植被 ----
        if (id.contains("leaves") || id.contains("moss") || id.contains("sapling")
                || id.contains("vine") || id.contains("fern") || id.contains("mushroom")) {
            return rgb(70, 136, 66);
        }
        // ---- 土壤类 ----
        if (id.contains("grass") && !id.contains("path")) return rgb(96, 162, 76);
        if (id.contains("dirt") || id.contains("podzol") || id.contains("mycelium")
                || id.contains("mud") || id.contains("grass_path") || id.contains("farmland")
                || id.contains("coarse")) return rgb(126, 92, 62);
        if (id.contains("sand") || id.contains("soul")) return rgb(219, 205, 139);
        if (id.contains("gravel")) return rgb(156, 146, 131);
        if (id.contains("clay")) return rgb(161, 146, 131);
        // ---- 彩色方块（混凝土/羊毛/地毯/陶瓦，按颜色词解析）----
        if (id.contains("concrete") || id.contains("wool") || id.contains("carpet")
                || id.contains("terracotta") || id.contains("concrete_powder")
                || id.contains("stained_glass") || id.contains("stained_glass_pane")) {
            int w = colorWordRGB(id);
            return w != -1 ? w : rgb(158, 150, 140);
        }
        // ---- 暗色岩（深板岩/黑石/玄武岩/基岩/_obsidian）先于 stone 命中 ----
        if (id.contains("obsidian") || id.contains("deepslate") || id.contains("blackstone")
                || id.contains("basalt") || id.contains("bedrock")) return rgb(40, 40, 48);
        if (id.contains("end_stone") || id.contains("endstone")) return rgb(220, 220, 180);
        if (id.contains("nether")) return rgb(112, 56, 46);
        // ---- 砖 / 圆石 ----
        if (id.contains("brick")) return rgb(150, 76, 60);
        if (id.contains("cobbl")) return rgb(121, 119, 116);
        // ---- 普通石族 ----
        if (id.contains("stone") || id.contains("andesite") || id.contains("diorite")
                || id.contains("granite") || id.contains("tuff") || id.contains("calcite")
                || id.contains("dripstone")) return rgb(128, 128, 128);
        // ---- 木质 ----
        if (id.contains("plank") || id.contains("wood") || id.contains("log")
                || id.contains("hyphae") || id.contains("fence") || id.contains("door")
                || id.contains("trapdoor") || id.contains("slab") && id.contains("wood")) {
            return rgb(161, 121, 76);
        }
        // ---- 铁轨 / 其他 ----
        if (id.contains("rail")) return rgb(96, 96, 102);
        if (id.contains("sponge")) return rgb(196, 178, 78);
        return 0; // 兜底走 mapColor
    }

    /** 颜色词 → RGB（keyword 匹配，light_* 先于 * 命中）。 */
    private static int colorWordRGB(String id) {
        for (Map.Entry<String, Integer> e : COLOR_WORDS.entrySet()) {
            if (id.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return -1;
    }

    private static final Map<String, Integer> COLOR_WORDS = new LinkedHashMap<>();
    static {
        COLOR_WORDS.put("white", rgb(214, 214, 214));
        COLOR_WORDS.put("light_gray", rgb(160, 167, 176));
        COLOR_WORDS.put("gray", rgb(110, 114, 120));
        COLOR_WORDS.put("black", rgb(34, 38, 42));
        COLOR_WORDS.put("red", rgb(160, 55, 50));
        COLOR_WORDS.put("orange", rgb(202, 92, 30));
        COLOR_WORDS.put("yellow", rgb(200, 176, 42));
        COLOR_WORDS.put("lime", rgb(122, 182, 42));
        COLOR_WORDS.put("green", rgb(82, 150, 72));
        COLOR_WORDS.put("cyan", rgb(40, 150, 162));
        COLOR_WORDS.put("light_blue", rgb(92, 152, 222));
        COLOR_WORDS.put("blue", rgb(50, 80, 200));
        COLOR_WORDS.put("purple", rgb(122, 62, 170));
        COLOR_WORDS.put("magenta", rgb(172, 62, 150));
        COLOR_WORDS.put("pink", rgb(212, 132, 172));
        COLOR_WORDS.put("brown", rgb(112, 76, 50));
    }

    // ---- 小工具 ----

    private static long chunkKey(int cx, int cz) {
        return ((long) cx & 0xFFFFFFFFL) | (((long) cz & 0xFFFFFFFFL) << 32);
    }

    /** 双顶高打包：high16=MOTION_BLOCKING 顶，low16=WORLD_SURFACE 顶（各 16bit 符号位保留）。 */
    private static int packTop(int motion, int surface) {
        return ((motion & 0xFFFF) << 16) | (surface & 0xFFFF);
    }

    private static int decodeMotion(int packed) {
        return (short) ((packed >> 16) & 0xFFFF);
    }

    private static int decodeSurface(int packed) {
        return (short) (packed & 0xFFFF);
    }

    /** 邻接高度差 → 山体阴影系数（NW 向光）。范围 0.75~1.15。 */
    private static float hillshade(int[] hm, int cols, int rows, int r, int c) {
        int hy = decodeMotion(hm[r * cols + c]);
        int hl = c > 0 ? decodeMotion(hm[r * cols + (c - 1)]) : hy;
        int hr = c < cols - 1 ? decodeMotion(hm[r * cols + (c + 1)]) : hy;
        int hu = r > 0 ? decodeMotion(hm[(r - 1) * cols + c]) : hy;
        int hd = r < rows - 1 ? decodeMotion(hm[(r + 1) * cols + c]) : hy;
        if (hl == UNSET) hl = hy;
        if (hr == UNSET) hr = hy;
        if (hu == UNSET) hu = hy;
        if (hd == UNSET) hd = hy;
        float gx = hl - hr;
        float gz = hu - hd;
        float shade = 1f - (gx + gz) * 0.012f;
        return clamp(shade, 0.75f, 1.15f);
    }

    private static int rgb(int r, int g, int b) {
        return 0xFF000000 | (clamp(r, 0, 255) << 16) | (clamp(g, 0, 255) << 8) | clamp(b, 0, 255);
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
