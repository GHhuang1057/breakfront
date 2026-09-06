package com.breakfront.client.ui;

import com.breakfront.client.bf.BfDraw;
import com.breakfront.client.bf.BfEasing;
import com.breakfront.client.bf.BfGlow;
import com.breakfront.client.bf.BfTheme;
import com.breakfront.client.hud.TerrainOverview;
import com.breakfront.client.state.ClientMatchState;
import com.breakfront.client.state.ClientMatchState.ZoneView;
import com.breakfront.game.Side;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 部署界面（BF2042 Deploy 结构）：
 *
 * - COUNTDOWN 模式（每局开战自动弹）：左侧「战区俯瞰」平面图 + 右侧 4 兵种卡 + 主武器选择；
 *   此时不渲染可部署点选择（仅展示据点拓扑与我方出生区，规划用）。
 * - RESPAWN 模式（战斗中死亡）：在俯瞰图中选择己方可部署入口（出生区 / 已控据点），
 *   点「部署」→ 发 /bf deploy &lt;zoneId|base|observe&gt; → vanilla respawn 落到所选点。
 *
 * 全矢量绘制。由 BreakfrontClient 按 phase 自动开合。
 */
public class BfDeployScreen extends Screen {

    /** 兵种展示数据：{id, 中文, 英文, 描述}。 */
    private static final String[][] CLASSES = {
            {"assault", "突击兵", "ASSAULT", "前线攻坚 · 推进占点"},
            {"engineer", "工程兵", "ENGINEER", "反载具 · 火箭筒"},
            {"support", "支援兵", "SUPPORT", "弹药补给 · 压制"},
            {"recon", "侦察兵", "RECON", "索敌标点 · 精确"},
    };

    /** 客户端兵种→可选主武器白名单（镜像 WeaponCatalog.CLASS_GUNS；client 无法 import core.weapon）。 */
    private static final Map<String, String[]> CLASS_GUNS = Map.of(
            "assault", new String[]{"hk416d", "m4a1"},
            "engineer", new String[]{"aa12", "m590"},
            "support", new String[]{"m249", "m4a1"},
            "recon", new String[]{"kar98", "mk14"});

    /** 枪 id → 展示名。 */
    private static final Map<String, String> GUN_LABEL = Map.of(
            "hk416d", "HK416D", "m4a1", "M4A1", "aa12", "AA-12", "m590", "M590A1",
            "m249", "M249", "kar98", "Kar98k", "mk14", "MK14", "ump45", "UMP45");

    // ---- 本地记忆（重启后丢失，届时 /bf kit 查回默认）----
    private static String selClass = "assault";
    private static String selGun = "hk416d";

    private final boolean respawnMode;
    private double age;
    private int hoverClass = -1;
    private int selectedDeployIndex = 0; // 俯瞰图选中目标（0=出生区）

    // 渲染期缓存（供鼠标命中检测）
    private int mapX, mapY, mapW, mapH;
    private double mapScale, mapOffX, mapOffY;

    // 地图视口：基础 fit（基于据点包围盒自算）+ 用户平移/缩放
    private double baseScale, baseOffX, baseOffY; // fit 基线（无平移/缩放）
    private double panX, panY;                     // 拖拽平移（px）
    private double zoom = 1.0;                      // 滚轮缩放倍率
    private int fitBx, fitBy, fitBw, fitBh;         // 复位(FIT)按钮屏幕矩形

    // 拖拽状态
    private boolean dragging = false;
    private boolean dragMoved = false;
    private double dragStartX, dragStartY;

    private final List<int[]> targetScreen = new ArrayList<>(); // {sx, sy, half, index}
    private final List<DeployTarget> targets = new ArrayList<>();
    private int deployBx, deployBy, deployBw, deployBh;
    private int obsBx, obsBy, obsBw, obsBh;
    private int wpPrevX, wpPrevY, wpNextX, wpNextY, wpBtnH;
    private int lastMx, lastMy; // 渲染期捕获的鼠标坐标（供无参 mouseIn 使用）

    public BfDeployScreen() {
        this(false);
    }

    public BfDeployScreen(boolean respawnMode) {
        super(Text.literal("DEPLOYMENT"));
        this.respawnMode = respawnMode;
    }

    public boolean isRespawnMode() {
        return respawnMode;
    }

    // ============================================================

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        this.age += delta;
        this.lastMx = mouseX;
        this.lastMy = mouseY;
        int sw = this.width;
        int sh = this.height;

        BfDraw.gradientV(ctx, 0, 0, sw, sh, 0xFF0A0D12, 0xFF141B26);
        BfDraw.parallelogram(ctx, -120, sh - 190, sw / 2, 5, 60, 0x14FFFFFF);
        BfDraw.parallelogram(ctx, sw / 3, -30, sw / 3, 4, -40, 0x0FFFFFFF);
        BfDraw.fill(ctx, 0, 0, 4, sh, BfTheme.TEAL);

        double in = BfEasing.staged(age, 0.05, 0.5);
        int a = (int) (255 * in);
        int rise = (int) ((1 - in) * 20);
        int pad = Math.max(30, sw / 22);
        int ty = pad + rise;

        int mySide = mySide();

        renderHeader(ctx, sw, sh, pad, ty, a);

        // 左：战区俯瞰
        int panelTop = ty + 64;
        int mapH = sh - panelTop - (respawnMode ? 170 : 90);
        renderMap(ctx, pad, panelTop, (int) Math.min(sw * 0.46, 600), mapH, mySide, mouseX, mouseY, a);

        // 右：兵种 + 武器
        int clsW = (int) Math.min(sw * 0.40, 380);
        int clsX = sw - pad - clsW;
        renderLoadout(ctx, clsX, clsW, panelTop, mapH, mouseX, mouseY, a);

        if (respawnMode) {
            renderFooter(ctx, sw, sh, pad, a);
        } else {
            String hint = "开战后自动关闭 · ESC 可提前返回战场";
            int hw = this.textRenderer.getWidth(hint);
            ctx.drawText(this.textRenderer, Text.literal(hint), sw / 2 - hw / 2, sh - 24,
                    argb(BfTheme.FAINT, a), false);
        }
    }

    // ---- 顶部标题 ----
    private void renderHeader(DrawContext ctx, int sw, int sh, int pad, int ty, int a) {
        if (respawnMode) {
            ctx.drawText(this.textRenderer, Text.literal("K.I.A.  你已阵亡"),
                    pad, ty, argb(BfTheme.RED, a), false);
            ctx.drawText(this.textRenderer, Text.literal("选择重生点继续作战"),
                    pad, ty + 14, argb(BfTheme.MUTED, a), false);
        } else {
            ctx.drawText(this.textRenderer, Text.literal("DEPLOYMENT  部署"),
                    pad, ty, argb(BfTheme.TEAL, a), false);
            ctx.drawText(this.textRenderer, Text.literal("ALL-OUT WARFARE  ·  全面战争"),
                    pad, ty + 13, argb(BfTheme.MUTED, a), false);

            String cd = String.format("%.0f", Math.max(0, ClientMatchState.countdownRemainingSeconds()));
            int cdW = this.textRenderer.getWidth(cd);
            ctx.drawText(this.textRenderer, Text.literal(cd), sw / 2 - cdW / 2, 40,
                    argb(BfTheme.TEAL, a), false);
            String lbl = "开战倒计时";
            int lw = this.textRenderer.getWidth(lbl);
            ctx.drawText(this.textRenderer, Text.literal(lbl), sw / 2 - lw / 2, 66,
                    argb(BfTheme.MUTED, a), false);
        }
    }

    // ---- 战区俯瞰平面图 ----
    private void renderMap(DrawContext ctx, int x, int y, int w, int h, int mySide,
                           int mx, int my, int a) {
        this.mapX = x;
        this.mapY = y;
        this.mapW = w;
        this.mapH = h;
        targetScreen.clear();
        targets.clear();

        BfDraw.fill(ctx, x, y, w, h, argb(BfTheme.PANEL, a));
        BfDraw.border(ctx, x, y, w, h, argb(BfTheme.PANEL_LINE, a));
        ctx.drawText(this.textRenderer, Text.literal("战区俯瞰  SECTOR MAP"),
                x + 14, y + 9, argb(BfTheme.TEXT_DIM, a), false);

        // 构建部署目标（出生区 + 当前扇区据点）
        double[] base = baseCoord(mySide);
        targets.add(new DeployTarget("base", "出生区", base[0], base[1], 0, true, -1, false));
        for (ZoneView z : ClientMatchState.zones()) {
            Side owner = Side.values()[z.ownerOrdinal()];
            // 争夺 = 守方名下且推进度>0；攻方已占区 owner=ATTACKER（meter 恒 1.0，稳固）
            boolean defContested = owner == Side.DEFENDER && z.meter() > 1e-3f;
            boolean valid;
            if (mySide == 0) {
                valid = owner == Side.ATTACKER;                       // 攻方已控据点
            } else if (mySide == 1) {
                valid = owner == Side.DEFENDER && !defContested;       // 守方稳固防守点
            } else {
                valid = false;
            }
            valid = respawnMode && valid;
            String label;
            if (owner == Side.ATTACKER) {
                label = z.letter() + " 已控";
            } else if (defContested) {
                label = z.letter() + " 争夺";
            } else {
                label = z.letter() + " 防守";
            }
            targets.add(new DeployTarget(z.zoneId(), label, z.worldX(), z.worldZ(), 1,
                    valid, owner.ordinal(), defContested));
        }

        // 计算世界坐标范围并 fit 到面板（基线，不含平移/缩放）
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (DeployTarget t : targets) {
            minX = Math.min(minX, t.wx()); maxX = Math.max(maxX, t.wx());
            minZ = Math.min(minZ, t.wz()); maxZ = Math.max(maxZ, t.wz());
        }
        if (minX == Double.MAX_VALUE) { minX = -10; maxX = 10; minZ = -10; maxZ = 10; }
        double spanX = Math.max(1, maxX - minX);
        double spanZ = Math.max(1, maxZ - minZ);
        int margin = 36;
        int availW = w - margin * 2;
        int availH = h - margin * 2 - 10;
        double scale = Math.min(availW / spanX, availH / spanZ);
        double drawW = spanX * scale, drawH = spanZ * scale;
        this.baseScale = scale;
        this.baseOffX = x + (w - drawW) / 2 - minX * scale;
        this.baseOffY = y + 24 + (availH - drawH) / 2 - minZ * scale;

        // 合成用户平移/缩放 → 有效变换（与 TerrainOverview 共用同一映射）
        double effScale = baseScale * zoom;
        double effOffX = baseOffX + panX;
        double effOffY = baseOffY + panY;
        this.mapScale = effScale;
        this.mapOffX = effOffX;
        this.mapOffY = effOffY;

        // 地形俯瞰底图（先画真实地形，再叠拓扑）
        ClientWorld world = this.client != null ? this.client.world : null;
        TerrainOverview.draw(ctx, world, x, y + 24, w, h - 34,
                effOffX, effOffY, effScale, TerrainOverview.DEFAULT_MAX_CELLS);

        // 网格（覆盖于地形之上，战术感）
        int gridN = 8;
        for (int i = 0; i <= gridN; i++) {
            int gx = (int) (x + margin + (w - margin * 2) * i / gridN);
            ctx.fill(gx, y + 24, gx + 1, y + h - 10, 0x14FFFFFF);
            int gy = (int) (y + 24 + (h - 34) * i / gridN);
            ctx.fill(x + margin, gy, x + w - margin, gy + 1, 0x14FFFFFF);
        }

        // 复位(FIT)按钮：回到初始 fit
        fitBw = 52; fitBh = 20;
        fitBx = x + w - fitBw - 10; fitBy = y + 8;
        boolean fitHov = mouseIn(fitBx, fitBy, fitBw, fitBh);
        BfDraw.fill(ctx, fitBx, fitBy, fitBw, fitBh, argb(fitHov ? 0xE6303E50 : BfTheme.PANEL, a));
        BfDraw.border(ctx, fitBx, fitBy, fitBw, fitBh, argb(fitHov ? BfTheme.TEAL : BfTheme.PANEL_LINE, a));
        ctx.drawText(this.textRenderer, Text.literal("FIT"), fitBx + 14, fitBy + 5,
                argb(fitHov ? BfTheme.TEAL : BfTheme.TEXT_DIM, a), false);

        // 目标方块（坐标换算统一走 TerrainOverview.worldToScreen，与地形底图严格对齐）
        for (int i = 0; i < targets.size(); i++) {
            DeployTarget t = targets.get(i);
            int sx = TerrainOverview.worldToScreenX(effOffX, effScale, t.wx());
            int sy = TerrainOverview.worldToScreenZ(effOffY, effScale, t.wz());
            boolean sel = i == selectedDeployIndex && respawnMode;
            int size;
            int col;
            if (t.kind() == 0) {
                col = BfTheme.TEAL;
                size = 26;
            } else {
                if (t.contested()) {
                    col = BfTheme.CYAN;
                } else if (t.owner() == 0) {
                    col = BfTheme.GREEN; // 攻方已控
                } else {
                    col = BfTheme.BLUE;  // 守方/防守
                }
                size = (int) Math.max(24, Math.min(90, 26 * effScale * 2));
            }
            int half = size / 2;
            // 可部署点高亮：合法=青白描边 + 辉光；非法（争夺/敌方）=红叉或灰
            if (t.valid()) {
                BfGlow.rect(ctx, sx - half - 2, sy - half - 2, size + 4, size + 4,
                        BfTheme.TEAL & 0xFFFFFF, 70, 6);
                BfDraw.border(ctx, sx - half - 2, sy - half - 2, size + 4, size + 4,
                        argb(BfTheme.TEAL, a));
            }
            // 底色（半透明）
            BfDraw.fill(ctx, sx - half, sy - half, size, size,
                    argb(col, t.valid() || t.kind() == 0 ? 60 : 26));
            // 选中辉光
            if (sel) {
                BfGlow.rect(ctx, sx - half, sy - half, size, size, col & 0xFFFFFF, 110, 8);
            }
            // 描边
            BfDraw.border(ctx, sx - half, sy - half, size, size,
                    argb(sel ? BfTheme.TEAL : col, a));
            if (t.kind() == 0) {
                // 出生区画菱形标记
                BfDraw.diamond(ctx, sx, sy, half - 4, argb(BfTheme.TEAL, a));
            } else if (!t.valid()) {
                // 不可部署：红叉（两条对角细带）
                int d = half - 6;
                int t = 2; // 半厚
                BfDraw.quad(ctx,
                        sx - d - t, sy - d + t,
                        sx - d + t, sy - d - t,
                        sx + d + t, sy + d - t,
                        sx + d - t, sy + d + t, argb(BfTheme.RED, a));
                BfDraw.quad(ctx,
                        sx + d - t, sy - d - t,
                        sx + d + t, sy - d + t,
                        sx - d + t, sy + d + t,
                        sx - d - t, sy + d - t, argb(BfTheme.RED, a));
            } else {
                ctx.drawText(this.textRenderer, Text.literal(t.label().substring(0, 1)),
                        sx - 4, sy - 8, argb(BfTheme.TEXT, a), false);
            }
            // 右侧 / 下方文字标签
            int lx = sx + half + 6;
            if (lx + 60 > x + w) {
                lx = sx - half - this.textRenderer.getWidth(t.label()) - 6;
            }
            ctx.drawText(this.textRenderer, Text.literal(t.label()),
                    lx, sy - 4, argb(t.valid() || t.kind() == 0 ? col : BfTheme.FAINT, a), false);

            targetScreen.add(new int[]{sx, sy, half, i});
        }

        // 玩家坐标菱形 + 文本（世界实时坐标，随平移/缩放移动）
        int px = TerrainOverview.worldToScreenX(effOffX, effScale, playerX());
        int pz = TerrainOverview.worldToScreenZ(effOffY, effScale, playerZ());
        BfGlow.rect(ctx, px - 5, pz - 5, 10, 10, BfTheme.TEAL & 0xFFFFFF, 60, 5);
        BfDraw.diamond(ctx, px, pz, 5, argb(BfTheme.TEAL, a));
        String me = String.format("你 (%.0f, %.0f)", playerX(), playerZ());
        ctx.drawText(this.textRenderer, Text.literal(me), x + 14, y + h - 18,
                argb(BfTheme.TEXT_DIM, a), false);
    }

    // ---- 兵种 + 主武器 ----
    private void renderLoadout(DrawContext ctx, int x, int w, int top, int h,
                                int mx, int my, int a) {
        // 校验当前选择合法
        normalizeSelection();

        ctx.drawText(this.textRenderer, Text.literal("兵种  ·  SELECT CLASS"),
                x, top - 24, argb(BfTheme.TEXT_DIM, a), false);

        int cardH = Math.min(56, h / 5);
        int gap = 8;
        hoverClass = -1;
        for (int i = 0; i < CLASSES.length; i++) {
            int cy = top + i * (cardH + gap);
            int ry = cy + cardH;
            boolean hov = mx >= x && mx <= x + w && my >= cy && my <= ry;
            if (hov) hoverClass = i;
            boolean sel = CLASSES[i][0].equals(selClass);
            int cardCol = argb(sel ? 0xE6303E50 : BfTheme.PANEL, a);
            BfDraw.fill(ctx, x, cy, w, cardH, cardCol);
            int edge = sel ? BfTheme.TEAL : (hov ? argb(BfTheme.TEXT_DIM, a) : argb(BfTheme.PANEL_LINE, a));
            BfDraw.border(ctx, x, cy, w, cardH, edge);
            if (sel) {
                BfDraw.fill(ctx, x, cy, 3, cardH, BfTheme.TEAL);
            }
            ctx.drawText(this.textRenderer, Text.literal(CLASSES[i][2]),
                    x + 14, cy + 9, argb(sel ? BfTheme.TEAL : BfTheme.TEXT, a), false);
            ctx.drawText(this.textRenderer, Text.literal(CLASSES[i][3]),
                    x + 14, cy + 24, argb(BfTheme.MUTED, a), false);
            String cn = CLASSES[i][1];
            int cnW = this.textRenderer.getWidth(cn);
            ctx.drawText(this.textRenderer, Text.literal(cn),
                    x + w - cnW - 12, cy + 8, argb(BfTheme.FAINT, a), false);
            if (sel) {
                BfDraw.fill(ctx, x + w - 26, cy + cardH / 2 - 6, 14, 12, BfTheme.TEAL);
                ctx.drawText(this.textRenderer, Text.literal("✓"),
                        x + w - 23, cy + cardH / 2 - 5, 0xFF0A0D12, false);
            }
        }

        // 主武器行
        int wTop = top + CLASSES.length * (cardH + gap) + 14;
        ctx.drawText(this.textRenderer, Text.literal("主武器  ·  PRIMARY"),
                x, wTop - 20, argb(BfTheme.TEXT_DIM, a), false);
        String[] guns = CLASS_GUNS.getOrDefault(selClass, new String[]{"hk416d"});
        String label = GUN_LABEL.getOrDefault(selGun, selGun);
        int bw = (int) (w * 0.7);
        int bx = x + 24;
        int by = wTop;
        int bh = 34;
        BfDraw.fill(ctx, bx, by, bw, bh, argb(BfTheme.PANEL, a));
        BfDraw.border(ctx, bx, by, bw, bh, argb(BfTheme.PANEL_LINE, a));
        int lw = this.textRenderer.getWidth(label);
        ctx.drawText(this.textRenderer, Text.literal(label), bx + bw / 2 - lw / 2, by + 10,
                argb(BfTheme.TEXT, a), false);
        // 左右切换箭头
        int arrowS = 30;
        int ay = by + (bh - arrowS) / 2;
        wpPrevX = bx - arrowS - 4; wpPrevY = ay;
        wpNextX = bx + bw + 4; wpNextY = ay; wpBtnH = arrowS;
        drawArrow(ctx, wpPrevX, ay, arrowS, false, mx, my, a);
        drawArrow(ctx, wpNextX, ay, arrowS, true, mx, my, a);

        // 武器白名单提示
        StringBuilder sb = new StringBuilder("可选：");
        for (String g : guns) sb.append(GUN_LABEL.getOrDefault(g, g)).append("  ");
        ctx.drawText(this.textRenderer, Text.literal(sb.toString()),
                x, by + bh + 8, argb(BfTheme.FAINT, a), false);
    }

    private void drawArrow(DrawContext ctx, int x, int y, int s, boolean right,
                           int mx, int my, int a) {
        boolean hov = mx >= x && mx <= x + s && my >= y && my <= y + s;
        BfDraw.fill(ctx, x, y, s, s, argb(hov ? 0xE6303E50 : BfTheme.PANEL, a));
        BfDraw.border(ctx, x, y, s, s, argb(hov ? BfTheme.TEAL : BfTheme.PANEL_LINE, a));
        String sym = right ? "›" : "‹";
        int sw2 = this.textRenderer.getWidth(sym);
        ctx.drawText(this.textRenderer, Text.literal(sym), x + s / 2 - sw2 / 2, y + s / 2 - 5,
                argb(BfTheme.TEXT, a), false);
    }

    // ---- 底部按钮（RESPAWN 模式）----
    private void renderFooter(DrawContext ctx, int sw, int sh, int pad, int a) {
        deployBw = Math.min(sw - 160, 360);
        deployBh = 46;
        deployBx = sw / 2 - deployBw / 2;
        deployBy = sh - 96;
        boolean hov = mouseIn(deployBx, deployBy, deployBw, deployBh);
        BfDraw.fill(ctx, deployBx, deployBy, deployBw, deployBh, hov ? 0xFF0A0D12 : BfTheme.TEAL);
        BfDraw.border(ctx, deployBx, deployBy, deployBw, deployBh, hov ? BfTheme.TEAL : BfTheme.TEAL_DIM);
        String txt = "部署  DEPLOY";
        int tw = this.textRenderer.getWidth(txt);
        ctx.drawText(this.textRenderer, Text.literal(txt), sw / 2 - tw / 2, deployBy + 15,
                hov ? BfTheme.TEAL : 0xFF0A0D12, false);

        // 观察按钮（旁观）
        obsBw = Math.min(sw - 160, 200);
        obsBh = 30;
        obsBx = sw / 2 - obsBw / 2;
        obsBy = deployBy + deployBh + 10;
        boolean hov2 = mouseIn(obsBx, obsBy, obsBw, obsBh);
        BfDraw.fill(ctx, obsBx, obsBy, obsBw, obsBh, argb(BfTheme.PANEL, a));
        BfDraw.border(ctx, obsBx, obsBy, obsBw, obsBh, argb(BfTheme.PANEL_LINE, a));
        String ot = "观察  OBSERVE";
        int ow2 = this.textRenderer.getWidth(ot);
        ctx.drawText(this.textRenderer, Text.literal(ot), sw / 2 - ow2 / 2, obsBy + 9,
                argb(BfTheme.TEXT_DIM, a), false);

        // 选中点信息
        if (selectedDeployIndex >= 0 && selectedDeployIndex < targets.size()) {
            DeployTarget t = targets.get(selectedDeployIndex);
            String info = "重生点：" + t.label()
                    + (t.valid() ? "" : (respawnMode ? " (不可部署)" : ""));
            int iw = this.textRenderer.getWidth(info);
            ctx.drawText(this.textRenderer, Text.literal(info), sw / 2 - iw / 2, deployBy - 22,
                    argb(t.valid() || !respawnMode ? BfTheme.TEAL : BfTheme.MUTED, a), false);
        }

        String stats = "本局击杀 " + myKills() + "  ·  爆头 " + myHeadshots();
        int stw = this.textRenderer.getWidth(stats);
        ctx.drawText(this.textRenderer, Text.literal(stats), sw / 2 - stw / 2, deployBy - 44,
                argb(BfTheme.TEXT_DIM, a), false);
    }

    // ============================================================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mx = (int) mouseX, my = (int) mouseY;
        if (respawnMode) {
            // 部署 / 观察
            if (mouseIn(deployBx, deployBy, deployBw, deployBh, mx, my)) {
                doDeploy();
                return true;
            }
            if (mouseIn(obsBx, obsBy, obsBw, obsBh, mx, my)) {
                doObserve();
                return true;
            }
        }

        // FIT 复位按钮
        if (mouseIn(fitBx, fitBy, fitBw, fitBh, mx, my)) {
            resetView();
            return true;
        }

        // 地图面板：按下即进入拖拽（拖拽=平移，松开未移动=选中目标）
        if (inMapPanel(mx, my)) {
            dragging = true;
            dragMoved = false;
            dragStartX = mouseX;
            dragStartY = mouseY;
            return true;
        }

        // 兵种卡
        if (hoverClass >= 0 && hoverClass < CLASSES.length) {
            selectClassByIndex(hoverClass);
            return true;
        }
        // 武器左右箭头
        if (mouseIn(wpPrevX, wpPrevY, wpBtnH, wpBtnH, mx, my)) { cycleWeapon(-1); return true; }
        if (mouseIn(wpNextX, wpNextY, wpBtnH, wpBtnH, mx, my)) { cycleWeapon(1); return true; }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging) {
            dragging = false;
            if (!dragMoved && respawnMode) {
                // 未拖动 → 视为点击：选中命中可部署目标
                int mx = (int) mouseX, my = (int) mouseY;
                for (int[] hit : targetScreen) {
                    int sx = hit[0], sy = hit[1], half = hit[2], idx = hit[3];
                    if (mx >= sx - half && mx <= sx + half && my >= sy - half && my <= sy + half) {
                        if (idx < targets.size() && targets.get(idx).valid()) {
                            selectedDeployIndex = idx;
                        }
                        return true;
                    }
                }
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double deltaX, double deltaY) {
        if (!dragging) {
            return false;
        }
        panX += deltaX;
        panY += deltaY;
        if (Math.abs(mouseX - dragStartX) + Math.abs(mouseY - dragStartY) > 3) {
            dragMoved = true;
        }
        clampPan();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontalAmount, double verticalAmount) {
        int mx = (int) mouseX, my = (int) mouseY;
        if (!inMapPanel(mx, my)) {
            return false;
        }
        // 以光标为锚缩放：保持光标下世界点不动
        double wx = TerrainOverview.screenToWorldX(mapOffX, mapScale, mx);
        double wz = TerrainOverview.screenToWorldZ(mapOffY, mapScale, my);
        zoom *= (verticalAmount > 0 ? 1.1 : 0.9);
        zoom = clamp(zoom, 0.35, 3.5);
        double newScale = baseScale * zoom;
        panX = (mx - wx * newScale) - baseOffX;
        panY = (my - wz * newScale) - baseOffY;
        clampPan();
        return true;
    }

    /** 回到初始 fit（去掉平移/缩放）。 */
    private void resetView() {
        panX = 0;
        panY = 0;
        zoom = 1.0;
    }

    /** 约束平移，避免地图整体移出面板（至少保留约 10% 可见）。 */
    private void clampPan() {
        double limX = mapW * 0.9;
        double limY = mapH * 0.9;
        panX = clamp(panX, -limX, limX);
        panY = clamp(panY, -limY, limY);
    }

    private boolean inMapPanel(int mx, int my) {
        return mx >= mapX && mx <= mapX + mapW && my >= mapY && my <= mapY + mapH;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (respawnMode) {
            if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_A) {
                cycleDeploy(-1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_D) {
                cycleDeploy(1);
                return true;
            }
        }
        if (keyCode == GLFW.GLFW_KEY_LEFT_BRACKET || keyCode == GLFW.GLFW_KEY_COMMA) {
            cycleWeapon(-1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT_BRACKET || keyCode == GLFW.GLFW_KEY_PERIOD) {
            cycleWeapon(1);
            return true;
        }
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_4) {
            selectClassByIndex(keyCode - GLFW.GLFW_KEY_1);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ---- 行为 ----

    private void doDeploy() {
        if (selectedDeployIndex < 0 || selectedDeployIndex >= targets.size()) {
            return;
        }
        DeployTarget t = targets.get(selectedDeployIndex);
        if (!t.valid()) {
            return; // 非法点（敌方/争夺/未分配）不部署
        }
        if (this.client != null && this.client.getNetworkHandler() != null) {
            this.client.getNetworkHandler().sendChatCommand("bf deploy " + t.id());
        }
        if (this.client != null && this.client.player != null) {
            this.client.player.requestRespawn();
            this.client.setScreen(null);
        }
    }

    private void doObserve() {
        if (this.client != null && this.client.getNetworkHandler() != null) {
            this.client.getNetworkHandler().sendChatCommand("bf deploy observe");
        }
        if (this.client != null) {
            this.client.setScreen(null);
        }
    }

    private void cycleDeploy(int dir) {
        if (targets.isEmpty()) {
            return;
        }
        int n = targets.size();
        int idx = selectedDeployIndex;
        for (int step = 1; step <= n; step++) {
            idx = (idx + dir + n) % n;
            if (targets.get(idx).valid()) {
                selectedDeployIndex = idx;
                return;
            }
        }
    }

    private void selectClassByIndex(int i) {
        if (i < 0 || i >= CLASSES.length) {
            return;
        }
        selClass = CLASSES[i][0];
        String[] guns = CLASS_GUNS.getOrDefault(selClass, new String[]{"hk416d"});
        selGun = guns[0];
        sendKit();
    }

    private void cycleWeapon(int dir) {
        String[] guns = CLASS_GUNS.getOrDefault(selClass, new String[]{"hk416d"});
        if (guns.length <= 1) {
            return;
        }
        int idx = 0;
        for (int i = 0; i < guns.length; i++) {
            if (guns[i].equals(selGun)) {
                idx = i;
                break;
            }
        }
        idx = (idx + dir + guns.length) % guns.length;
        selGun = guns[idx];
        sendKit();
    }

    private void sendKit() {
        if (this.client != null && this.client.getNetworkHandler() != null) {
            this.client.getNetworkHandler().sendChatCommand("bf kit " + selClass + " " + selGun);
        }
    }

    /** 确保 selGun 属于当前兵种白名单；否则回退首把。 */
    private void normalizeSelection() {
        String[] guns = CLASS_GUNS.getOrDefault(selClass, new String[]{"hk416d"});
        boolean ok = false;
        for (String g : guns) {
            if (g.equals(selGun)) {
                ok = true;
                break;
            }
        }
        if (!ok) {
            selGun = guns[0];
        }
    }

    // ---- 辅助 ----

    private int mySide() {
        if (this.client == null || this.client.player == null) {
            return -1;
        }
        String me = this.client.player.getName().getString();
        for (ClientMatchState.BoardRow r : ClientMatchState.board()) {
            if (r.name().equals(me)) {
                return r.sideOrdinal();
            }
        }
        return -1;
    }

    /** 己方出生区近似世界坐标（供俯瞰图标用；实际落点由服务端 spawnFor 决定）。 */
    private double[] baseCoord(int mySide) {
        List<ZoneView> zones = ClientMatchState.zones();
        if (zones.isEmpty()) {
            return new double[]{0, 0};
        }
        if (mySide == 1) { // 守方：末据点外侧
            ZoneView z = zones.get(zones.size() - 1);
            return new double[]{z.worldX() + z.radius() + 20, z.worldZ()};
        }
        // 攻方（默认）：首据点外侧
        ZoneView z = zones.get(0);
        return new double[]{z.worldX() - (z.radius() + 20), z.worldZ()};
    }

    private double playerX() {
        return this.client != null && this.client.player != null ? this.client.player.getX() : 0;
    }

    private double playerZ() {
        return this.client != null && this.client.player != null ? this.client.player.getZ() : 0;
    }

    private boolean mouseIn(int x, int y, int w, int h, int mx, int my) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private boolean mouseIn(int x, int y, int w, int h) {
        return mouseIn(x, y, w, h, lastMx, lastMy);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    @Override
    public void removed() {
        // 切屏（LOBBY / 死亡 / 部署完成）：释放地形采样缓存，避免跨局泄漏
        TerrainOverview.release();
        super.removed();
    }

    private int myKills() {
        String me = this.client != null && this.client.player != null
                ? this.client.player.getName().getString() : "";
        for (ClientMatchState.BoardRow r : ClientMatchState.board()) {
            if (r.name().equals(me)) {
                return r.kills();
            }
        }
        return 0;
    }

    private int myHeadshots() {
        String me = this.client != null && this.client.player != null
                ? this.client.player.getName().getString() : "";
        for (ClientMatchState.BoardRow r : ClientMatchState.board()) {
            if (r.name().equals(me)) {
                return r.headshots();
            }
        }
        return 0;
    }

    /** 俯瞰图部署目标。 */
    private record DeployTarget(String id, String label, double wx, double wz, int kind,
                               boolean valid, int owner, boolean contested) {
    }

    private static int argb(int rgb, int alpha) {
        int aa = Math.max(0, Math.min(255, alpha));
        return (aa << 24) | (rgb & 0xFFFFFF);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !respawnMode; // 阵亡部署必须点击部署/观察，不能 ESC 逃避
    }
}
