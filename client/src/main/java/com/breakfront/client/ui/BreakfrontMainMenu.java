package com.breakfront.client.ui;

import com.breakfront.client.bf.BfDraw;
import com.breakfront.client.bf.BfEasing;
import com.breakfront.client.bf.BfGlow;
import com.breakfront.client.bf.BfServerConfig;
import com.breakfront.client.bf.BfTheme;
import com.breakfront.client.upd.Updater;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.network.CookieStorage;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BREAKFRONT 主菜单 —— 按 Battlefield 2042 的信息结构还原：
 *
 * [顶栏] 左：字标｜中：横向导航 PLAY / 单机 / 设置 / 退出（选中黄线、hover 白线扩展）
 *        右：更新源状态徽章 + 版本
 * [主体] 模式卡片组：ALL-OUT WARFARE 主卡（战场渐变 + 城市剪影 + 部署按钮），
 *        右侧 PORTAL / 危险区「即将推出」小卡
 * [底部] 键位提示条（BF 惯例）
 * [部署] 全屏加载层：模式名 + 状态文案 + 不定态进度条（BF 连接流程观感）
 *
 * 交互动效：进场 stagger 浮入、导航线扩展、卡片 hover 边框增亮 + 上移 1px、
 * 部署按钮 hover 反白、进度条扫动、背景双层天际线视差。
 */
public class BreakfrontMainMenu extends Screen {

    private enum Flow { IDLE, CHECKING, CONNECTING, NEED_RESTART }

    private static final String[] NAV_LABELS = {"PLAY", "设置", "退出"};

    // 布局（init 时计算）
    private int topBarH;
    private int[] navX;          // 每个 tab 的左缘
    private int[] navW;
    private int cardX;
    private int cardY;
    private int cardW;
    private int cardH;
    private int sideX;
    private int sideW;
    private int sideCardH;
    private int deployBtnW = 168;
    private int deployBtnH = 44;

    // 运行态
    private double age;
    private Flow flow = Flow.IDLE;
    private boolean restarting = false;              // 自动重启已触发（防重入）
    private long restartDeadline = Long.MAX_VALUE;   // 待重启自动倒计时截止(ms)
    private String flowMsg = "";
    private String flowTitle = "ALL-OUT WARFARE"; // 当前部署加载层的模式名
    private final List<String> noticeLines = new ArrayList<>();
    private final float[] navHover = new float[NAV_LABELS.length];
    private float cardHover;
    private float deployHover;

    /** 每次启动只检查一次更新（跨菜单实例共享）。 */
    private static volatile boolean sessionCheckStarted = false;
    private static volatile Updater.Result sessionResult;
    /** 检查开始的毫秒时间（徽章超时提示用）。 */
    private static volatile long sessionCheckStartMs;

    private final ExecutorService ioPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "breakfront-ui-io");
        t.setDaemon(true);
        return t;
    });

    public BreakfrontMainMenu() {
        super(Text.literal("BREAKFRONT"));
    }

    @Override
    protected void init() {
        int sw = this.width;
        int sh = this.height;
        topBarH = Math.max(30, sh / 10);

        // 导航测量
        navX = new int[NAV_LABELS.length];
        navW = new int[NAV_LABELS.length];
        int logoW = this.textRenderer.getWidth("BREAKFRONT") + 44;
        int cx = 16 + logoW + 26;
        for (int i = 0; i < NAV_LABELS.length; i++) {
            navW[i] = this.textRenderer.getWidth(NAV_LABELS[i]) + 22;
            navX[i] = cx;
            cx += navW[i] + 6;
        }

        // 模式卡片
        int pad = Math.max(18, sw / 26);
        int bottomBar = 30;
        cardX = pad;
        cardY = topBarH + 16;
        cardH = sh - cardY - bottomBar - 14;
        sideW = (int) Math.min(190, sw * 0.22);
        cardW = (int) Math.max(300, sw - pad * 2 - sideW - 16);
        sideX = cardX + cardW + 16;
        sideCardH = (cardH - 12) / 2;

        // 启动即检查更新（本会话一次）；结果驱动徽章与重启卡片
        if (sessionResult != null && !sessionResult.proceedToConnect()) {
            flow = Flow.NEED_RESTART;
            armRestart();
            wrap(noticeLines, sessionResult.message(), (int) Math.min(width * 0.66, 470) - 60);
        } else {
            flow = Flow.IDLE;
        }
        age = 0;
        if (!sessionCheckStarted) {
            sessionCheckStarted = true;
            sessionCheckStartMs = System.currentTimeMillis();
            ioPool.execute(() -> {
                Updater.Result r = Updater.run(BfServerConfig.host(), BfServerConfig.updatePort());
                client.execute(() -> applyStartupResult(r));
            });
        }
    }

    private void applyStartupResult(Updater.Result r) {
        sessionResult = r;
        if (flow == Flow.IDLE) {
            if (!r.proceedToConnect()) {
                flow = Flow.NEED_RESTART;
                armRestart();
                wrap(noticeLines, r.message(), (int) Math.min(width * 0.66, 470) - 60);
            }
        }
    }

    /** 进入待重启态：启动 6 秒自动重启倒计时（点「稍后」可暂停）。 */
    private void armRestart() {
        restarting = false;
        restartDeadline = System.currentTimeMillis() + 6000;
    }

    /** 自动重启：武装影子替换进程 → 退出游戏（退出瞬间由影子脚本替换 mods 并拉起新进程）。 */
    private void doRestartNow() {
        if (restarting) {
            return;
        }
        restarting = true;
        if (Updater.armAutoApply()) {
            client.scheduleStop();
        } else {
            // 武装失败：保留暂存，停在卡片上让用户点「立即重启」重试或手动跑 bat
            restarting = false;
            restartDeadline = Long.MAX_VALUE;
        }
    }

    // ================= 渲染 =================

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        this.age += delta;
        int sw = this.width;
        int sh = this.height;
        long now = System.currentTimeMillis();

        drawBackground(ctx, sw, sh, now);
        drawTopBar(ctx, sw, mouseX, mouseY, delta);
        drawMainCard(ctx, mouseX, mouseY, delta, now);
        drawSideCards(ctx, mouseX, mouseY);
        drawBottomHints(ctx, sw, sh);

        if (flow == Flow.NEED_RESTART) {
            drawRestartCard(ctx, sw, sh, now);
            // 倒计时归零 → 自动重启（一次）
            if (!restarting && restartDeadline != Long.MAX_VALUE && now >= restartDeadline) {
                doRestartNow();
            }
        } else if (flow == Flow.CHECKING || flow == Flow.CONNECTING) {
            drawDeployLoading(ctx, sw, sh, now);
        }
    }

    private void drawBackground(DrawContext ctx, int sw, int sh, long now) {
        BfDraw.gradientV(ctx, 0, 0, sw, sh, BfTheme.BG_DEEP, BfTheme.BG_UP);
        // 地平线光带 + 双层视差天际线（远慢近快）
        int horizon = sh - 70;
        BfDraw.skyline(ctx, horizon, age * 4.0, BfDraw.skylineFar(), 0x12FFFFFF);
        BfDraw.skyline(ctx, horizon + 6, age * 10.0, BfDraw.skylineNear(), 0x1EFFFFFF);
        // 高架桥剪影（一条横贯线 + 立柱，呼应城市战场）
        BfDraw.fill(ctx, 0, horizon - 34, sw, 2, 0x22FFFFFF);
        for (int x = (int) (-((age * 10) % 90)); x < sw; x += 90) {
            BfDraw.fill(ctx, x, horizon - 34, 3, 30, 0x1CFFFFFF);
        }
        BfDraw.fill(ctx, 0, horizon, sw, 1, 0x30FFFFFF);
        // 地面渐隐
        BfDraw.gradientV(ctx, 0, horizon, sw, sh - horizon, 0x0006080C, 0xFF0A0D12);
    }

    private void drawTopBar(DrawContext ctx, int sw, int mouseX, int mouseY, float delta) {
        double in = BfEasing.staged(age, 0.0, 0.4);
        int slide = (int) ((1 - in) * -12);
        int a = (int) (255 * in);

        // 顶栏底色 + 分隔线
        BfDraw.gradientV(ctx, 0, 0, sw, topBarH, 0xF2070A0E, 0xD2070A0E);
        BfDraw.fill(ctx, 0, topBarH - 1, sw, 1, 0x26FFFFFF);

        // 字标
        int textY = topBarH / 2 - this.textRenderer.fontHeight / 2 + slide;
        BfDraw.diamond(ctx, 22, topBarH / 2.0 + slide, 6.5, BfTheme.TEAL);
        ctx.drawText(this.textRenderer, Text.literal("BREAKFRONT"), 36, textY,
                argb(BfTheme.TEXT, a), false);
        int w0 = this.textRenderer.getWidth("BREAKFRONT");
        ctx.drawText(this.textRenderer, Text.literal("破阵前线"), 36 + w0 + 10, textY,
                argb(BfTheme.MUTED, a), false);

        // 导航 tabs
        for (int i = 0; i < NAV_LABELS.length; i++) {
            boolean hov = mouseX >= navX[i] && mouseX <= navX[i] + navW[i]
                    && mouseY >= 0 && mouseY <= topBarH;
            navHover[i] = smooth(navHover[i], hov ? 1 : 0, delta);
            double h = BfEasing.easeOutCubic(navHover[i]);
            boolean selected = i == 0;

            int col = selected ? BfTheme.TEAL : argb(hov ? BfTheme.TEXT : BfTheme.TEXT_DIM, a);
            int tx = navX[i] + (int) (h * 2);
            ctx.drawText(this.textRenderer, Text.literal(NAV_LABELS[i]), tx, textY, col, false);

            // 选中常亮青线（带辉光）/ hover 白线扩展
            if (selected) {
                BfGlow.strip(ctx, navX[i], topBarH - 6, navW[i] - 6, 2,
                        BfTheme.TEAL & 0xFFFFFF, 60);
                BfDraw.fill(ctx, navX[i], topBarH - 3, navW[i] - 6, 2, BfTheme.TEAL);
            } else if (h > 0.03) {
                int lw = (int) ((navW[i] - 6) * h);
                BfDraw.fill(ctx, navX[i], topBarH - 3, lw, 2, argb(0xFFFFFFFF, (int) (180 * h * a / 255)));
            }
        }

        // 右侧：状态徽章 + 版本
        String ver = FabricLoader.getInstance().getModContainer("breakfront-client")
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
        String statusText = statusBadgeText();
        int stW = this.textRenderer.getWidth(statusText);
        int rx = sw - stW - 54;
        BfDraw.fill(ctx, rx, topBarH / 2 - 2 + slide, 5, 5, statusBadgeColor());
        ctx.drawText(this.textRenderer, Text.literal(statusText), rx + 10, textY, argb(BfTheme.MUTED, a), false);
        String vTag = "v" + ver;
        int vw = this.textRenderer.getWidth(vTag);
        ctx.drawText(this.textRenderer, Text.literal(vTag), sw - vw - 14, textY, argb(BfTheme.FAINT, a), false);
    }

    private String statusBadgeText() {
        if (sessionResult != null && !sessionResult.proceedToConnect()) {
            return "待重启应用";
        }
        if (!sessionCheckStarted) {
            return "更新源在线";
        }
        if (sessionResult == null) {
            // 长时间未返回：提示响应慢（更新源经 frp 首请求可能较慢）
            if (sessionCheckStartMs > 0
                    && System.currentTimeMillis() - sessionCheckStartMs > 7000) {
                return "更新源响应慢，稍候…";
            }
            return "检查更新中";
        }
        return switch (sessionResult.outcome()) {
            case OK -> "已是最新";
            case UPDATED_REQUIRES_RESTART -> "待重启应用";
            case SKIPPED_NO_SOURCE -> "更新源离线";
            case ERROR -> "更新失败";
        };
    }

    private int statusBadgeColor() {
        if (sessionResult != null && !sessionResult.proceedToConnect()) {
            return BfTheme.TEAL;
        }
        if (!sessionCheckStarted) {
            return BfTheme.GREEN;
        }
        if (sessionResult == null) {
            return BfTheme.AMBER;
        }
        return switch (sessionResult.outcome()) {
            case OK -> BfTheme.GREEN;
            case UPDATED_REQUIRES_RESTART -> BfTheme.TEAL;
            default -> 0xFF6B7280;
        };
    }

    private void drawMainCard(DrawContext ctx, int mouseX, int mouseY, float delta, long now) {
        double in = BfEasing.staged(age, 0.10, 0.5);
        int rise = (int) ((1 - in) * 22);
        int a = (int) (255 * in);
        int x = cardX;
        int y = cardY + rise;

        boolean hov = flow == Flow.IDLE && inRect(mouseX, mouseY, x, cardY, cardW, cardH);
        cardHover = smooth(cardHover, hov ? 1 : 0, delta);
        double ch = BfEasing.easeOutCubic(cardHover);

        // 卡片底
        BfDraw.fill(ctx, x, y, cardW, cardH, argb(BfTheme.PANEL, a));
        BfDraw.border(ctx, x, y, cardW, cardH,
                argb(BfTheme.TEAL_DIM, (int) (a * (0.35 + 0.65 * ch))));

        // 卡内战场背景（裁剪）：渐变 + 淡化天际线
        ctx.enableScissor(x, y, x + cardW, y + cardH);
        BfDraw.gradientV(ctx, x, y, cardW, cardH, 0xFF10161F, 0xFF1B2430);
        int innerHorizon = y + cardH - 46;
        BfDraw.skyline(ctx, innerHorizon, age * 6.0 + x, BfDraw.skylineFar(), 0x14FFFFFF);
        BfDraw.skyline(ctx, innerHorizon + 4, age * 14.0 + x * 2, BfDraw.skylineNear(), 0x1FFFFFFF);
        BfDraw.fill(ctx, x, innerHorizon, cardW, 1, 0x24FFFFFF);
        // hover 亮化：内侧左缘黄条随 hover 展开
        int edge = (int) (4 * ch);
        if (edge > 0) {
            BfDraw.fill(ctx, x, y, edge, cardH, argb(BfTheme.TEAL, (int) (200 * ch * a / 255)));
        }
        ctx.disableScissor();

        // 文案
        int padX = x + 26;
        int py = y + 24;
        ctx.drawText(this.textRenderer, Text.literal("ALL-OUT WARFARE"),
                padX, py, argb(BfTheme.TEAL, a), false);
        ctx.drawText(this.textRenderer, Text.literal("全面战争"),
                padX, py + 13, argb(BfTheme.TEXT, a), false);
        ctx.drawText(this.textRenderer,
                Text.literal("32v32 大战场 · 攻防 Breakthrough · 缺员自动由 AI 补位"),
                padX, py + 30, argb(BfTheme.MUTED, a), false);

        // 服务器信息行（小标签 + 数值排版）
        int infoY = y + cardH - deployBtnH - 40;
        drawInfoColumn(ctx, padX, infoY, argb(0xFFFFFFFF, a));

        // 部署按钮（BF 主行动：黄底黑字，hover 反白）
        int bx = padX;
        int by = y + cardH - deployBtnH - 18;
        boolean bHov = flow == Flow.IDLE
                && inRect(mouseX, mouseY, bx, by, deployBtnW, deployBtnH);
        deployHover = smooth(deployHover, bHov ? 1 : 0, delta);
        double dh = BfEasing.easeOutCubic(deployHover);
        if (flow == Flow.IDLE) {
            // 辉光层 + 底层青（BF 主行动）
            BfGlow.rect(ctx, bx - 3, by - 3, deployBtnW + 6, deployBtnH + 6,
                    BfTheme.TEAL & 0xFFFFFF, 72, 7);
            BfDraw.parallelogram(ctx, bx, by, deployBtnW, deployBtnH, 6, argb(BfTheme.TEAL, a));
            // 上层：白（hover 反白过渡）
            BfDraw.parallelogram(ctx, bx, by, deployBtnW, deployBtnH, 6,
                    argb(0xFFFFFFFF, (int) (255 * dh)));
            int tcol = dh > 0.5 ? 0xFF0A0D12 : 0xFF0A0D12;
            String label = "部 署";
            int lw = this.textRenderer.getWidth(label);
            ctx.drawText(this.textRenderer, Text.literal(label),
                    bx + deployBtnW / 2 - lw / 2 + (int) (dh * 2),
                    by + deployBtnH / 2 - this.textRenderer.fontHeight / 2, tcol, false);
            // 右缘小箭头（几何）
            int ax = bx + deployBtnW - 20;
            int ay = by + deployBtnH / 2;
            BfDraw.parallelogram(ctx, ax, ay - 4, 8, 8, 3, 0xFF0A0D12);
        }
    }

    private void drawInfoColumn(DrawContext ctx, int x, int y, int argb) {
        String[] labels = {"服务器", "模式", "规模"};
        String[] values = {
                BfServerConfig.address(),
                "攻防 · 2 扇区",
                "32 v 32 · AI 补位"
        };
        for (int i = 0; i < labels.length; i++) {
            ctx.drawText(this.textRenderer, Text.literal(labels[i]), x, y + i * 13,
                    argb(BfTheme.MUTED, (argb >>> 24)), false);
            ctx.drawText(this.textRenderer, Text.literal(values[i]), x + 46, y + i * 13, argb, false);
        }
    }

    private void drawSideCards(DrawContext ctx, int mouseX, int mouseY) {
        String[][] cards = {
                {"CONQUEST 征服", "模式池 · 后续开放", "即将推出"},
                {"BATTLEFIELD PORTAL", "门户 · 自定义规则战场", "即将推出"}
        };
        boolean[] locked = {true, true};
        for (int i = 0; i < cards.length; i++) {
            double in = BfEasing.staged(age, 0.22 + i * 0.08, 0.45);
            int slide = (int) ((1 - in) * 14);
            int a = (int) (255 * in);
            int x = sideX;
            int y = cardY + i * (sideCardH + 12) + slide;
            boolean hov = flow == Flow.IDLE
                    && mouseX >= x && mouseX <= x + sideW && mouseY >= y && mouseY <= y + sideCardH;
            BfDraw.fill(ctx, x, y, sideW, sideCardH,
                    argb(hov && !locked[i] ? 0xE6212A36 : 0xE6161C25, a));
            BfDraw.border(ctx, x, y, sideW, sideCardH,
                    argb(hov && !locked[i] ? BfTheme.TEAL_DIM : BfTheme.PANEL_LINE, a));
            ctx.drawText(this.textRenderer, Text.literal(cards[i][0]), x + 12, y + 12,
                    argb(locked[i] ? BfTheme.TEAL_DIM : BfTheme.TEAL, a), false);
            ctx.drawText(this.textRenderer, Text.literal(cards[i][1]), x + 12, y + 25,
                    argb(BfTheme.TEXT_DIM, a), false);
            if (locked[i]) {
                // 锁形角标（几何）
                BfDraw.fill(ctx, x + sideW - 16, y + sideCardH - 18, 8, 7, argb(BfTheme.MUTED, a));
                BfDraw.border(ctx, x + sideW - 18, y + sideCardH - 22, 12, 6, argb(BfTheme.MUTED, a));
            }
            ctx.drawText(this.textRenderer, Text.literal(cards[i][2]), x + 12, y + sideCardH - 15,
                    argb(locked[i] ? BfTheme.FAINT : BfTheme.TEXT_DIM, a), false);
        }
    }

    private void drawBottomHints(DrawContext ctx, int sw, int sh) {
        double in = BfEasing.staged(age, 0.38, 0.5);
        int a = (int) (255 * in);
        int y = sh - 22;
        BfDraw.fill(ctx, 0, y - 6, sw, 1, argb(BfTheme.PANEL_LINE, a));
        String left = "ENTER 部署    鼠标  选择    ESC  无操作";
        ctx.drawText(this.textRenderer, Text.literal(left), 16, y, argb(BfTheme.MUTED, a), false);
        String right = "BREAKFRONT · BF2042 UI";
        int rw = this.textRenderer.getWidth(right);
        ctx.drawText(this.textRenderer, Text.literal(right), sw - rw - 16, y, argb(BfTheme.FAINT, a), false);
    }

    private void drawDeployLoading(DrawContext ctx, int sw, int sh, long now) {
        BfDraw.fill(ctx, 0, 0, sw, sh, 0xF206080C);
        // 顶部：模式名（大写小字）+ 状态大字
        ctx.drawText(this.textRenderer, Text.literal(flowTitle), sw / 2
                - this.textRenderer.getWidth(flowTitle) / 2, sh / 2 - 52, BfTheme.TEAL, false);
        int lw = this.textRenderer.getWidth(flowMsg);
        ctx.drawText(this.textRenderer, Text.literal(flowMsg), sw / 2 - lw / 2, sh / 2 - 30,
                BfTheme.TEXT, false);
        // 不定态进度条
        int bw = (int) (sw * 0.42);
        BfDraw.progressBar(ctx, sw / 2 - bw / 2, sh / 2 + 6, bw, 3, -1, now, BfTheme.TEAL);
        // 目标地址
        String target = BfServerConfig.address();
        int tw = this.textRenderer.getWidth(target);
        ctx.drawText(this.textRenderer, Text.literal(target), sw / 2 - tw / 2, sh / 2 + 22,
                BfTheme.MUTED, false);
        // 底部细字
        String tip = "连接由服务端发起 · 未满员位置由 AI 自动补位 · 请保持网络畅通";
        int gw = this.textRenderer.getWidth(tip);
        ctx.drawText(this.textRenderer, Text.literal(tip), sw / 2 - gw / 2, sh - 34, BfTheme.FAINT, false);
    }

    private void drawRestartCard(DrawContext ctx, int sw, int sh, long now) {
        BfDraw.fill(ctx, 0, 0, sw, sh, 0xD806080C);
        int cw = (int) Math.min(sw * 0.66, 470);
        int ch = 150;
        int x = sw / 2 - cw / 2;
        int y = sh / 2 - ch / 2;
        BfDraw.fill(ctx, x, y, cw, ch, BfTheme.PANEL);
        BfDraw.fill(ctx, x, y, 4, ch, BfTheme.TEAL);
        ctx.drawText(this.textRenderer, Text.literal("模组已更新 · 自动重启中"), x + 24, y + 18, BfTheme.TEAL, false);
        int ly = y + 42;
        for (String line : noticeLines) {
            ctx.drawText(this.textRenderer, Text.literal(line), x + 24, ly, BfTheme.TEXT_DIM, false);
            ly += 14;
        }
        // 倒计时/暂停状态提示
        String hint;
        if (restartDeadline != Long.MAX_VALUE) {
            int cnt = (int) Math.max(1, (restartDeadline - now + 999) / 1000);
            hint = cnt + " 秒后自动应用更新并重启游戏（替换 mods 无需手动操作）";
        } else {
            hint = "自动重启已暂停 · 点击「立即重启」应用更新";
        }
        int hw = this.textRenderer.getWidth(hint);
        ctx.drawText(this.textRenderer, Text.literal(hint),
                x + cw / 2 - hw / 2, y + ch - 48, BfTheme.AMBER, false);
        // 按钮：立即重启（黄实底） / 稍后（描边）
        int bW = 128;
        int bH = 30;
        int by = y + ch - bH - 14;
        BfDraw.parallelogram(ctx, x + cw - bW * 2 - 34, by, bW, bH, 5, BfTheme.TEAL);
        int t1w = this.textRenderer.getWidth("立即重启");
        ctx.drawText(this.textRenderer, Text.literal("立即重启"),
                x + cw - bW * 2 - 34 + bW / 2 - t1w / 2, by + bH / 2 - 4, 0xFF0A0D12, false);
        BfDraw.border(ctx, x + cw - bW - 22, by, bW, bH, BfTheme.PANEL_LINE);
        int t2w = this.textRenderer.getWidth("稍后");
        ctx.drawText(this.textRenderer, Text.literal("稍后"),
                x + cw - bW - 22 + bW / 2 - t2w / 2, by + bH / 2 - 4, BfTheme.TEXT_DIM, false);
    }

    // ================= 交互 =================

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) {
            return false;
        }
        // 重启卡片
        if (flow == Flow.NEED_RESTART) {
            int cw = (int) Math.min(width * 0.66, 470);
            int ch = 150;
            int x = width / 2 - cw / 2;
            int y = height / 2 - ch / 2;
            int bW = 128;
            int bH = 30;
            int by = y + ch - bH - 14;
            if (inRect(mx, my, x + cw - bW * 2 - 34, by, bW, bH)) {
                doRestartNow();
                return true;
            }
            if (inRect(mx, my, x + cw - bW - 22, by, bW, bH)) {
                restartDeadline = Long.MAX_VALUE; // 暂停自动重启，稍后手动
                return true;
            }
            return false;
        }
        if (flow != Flow.IDLE) {
            return false;
        }
        // 顶栏导航
        if (my <= topBarH) {
            for (int i = 0; i < NAV_LABELS.length; i++) {
                if (mx >= navX[i] && mx <= navX[i] + navW[i]) {
                    switch (i) {
                        case 0 -> onDeploy();
                        case 1 -> client.setScreen(new OptionsScreen(this, client.options));
                        case 2 -> client.scheduleStop();
                        default -> {
                        }
                    }
                    return true;
                }
            }
            return false;
        }
        // 主卡 / 部署按钮
        boolean inCard = inRect(mx, my, cardX, cardY, cardW, cardH);
        if (inCard) {
            onDeploy();
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (flow == Flow.IDLE && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            onDeploy();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void onDeploy() {
        startFlow("ALL-OUT WARFARE");
    }

    private void startFlow(String title) {
        flowTitle = title;
        // 启动检查已完成且无阻塞 → 直接连（部署不重复检查）
        if (sessionResult != null) {
            if (sessionResult.proceedToConnect()) {
                startConnect();
            } else {
                // 有待应用的更新：重新弹出提示卡片（自动重启）
                flow = Flow.NEED_RESTART;
                armRestart();
                wrap(noticeLines, sessionResult.message(), (int) Math.min(width * 0.66, 470) - 60);
            }
            return;
        }
        // 启动检查未完成（极少见）：现场兜底检查
        flow = Flow.CHECKING;
        flowMsg = "正在校验模组版本";
        String host = BfServerConfig.host();
        int updPort = BfServerConfig.updatePort();
        ioPool.execute(() -> {
            Updater.Result r = Updater.run(host, updPort);
            client.execute(() -> onUpdateResult(r));
        });
    }

    private void onUpdateResult(Updater.Result r) {
        sessionResult = r;
        if (r.proceedToConnect()) {
            startConnect();
            return;
        }
        flow = Flow.NEED_RESTART;
        armRestart();
        wrap(noticeLines, r.message(), (int) Math.min(width * 0.66, 470) - 60);
    }

    private void startConnect() {
        flow = Flow.CONNECTING;
        flowMsg = "正在连接服务器";
        try {
            String addr = BfServerConfig.address();
            // 公开入口只有带 parent 的 connect（vanilla 多人列表同款）。
            // “该服务器不支持转移”实为服务端默认 accepts-transfers=false 拒绝转移式登录，
            // 服务端需开启 accepts-transfers=true（server.properties）一并根治。
            ConnectScreen.connect(this, client, ServerAddress.parse(addr),
                    new ServerInfo("BREAKFRONT", addr, ServerInfo.ServerType.OTHER),
                    false, new CookieStorage(new HashMap<>()));
        } catch (Exception e) {
            flow = Flow.IDLE;
        }
    }

    // ================= 工具 =================

    private float smooth(float cur, float target, float delta) {
        float k = (float) Math.min(1, delta * 14);
        return cur + (target - cur) * k;
    }

    private static boolean inRect(double mx, double my, double x, double y, double w, double h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static int argb(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    private static void wrap(List<String> out, String text, int maxPx) {
        out.clear();
        StringBuilder line = new StringBuilder();
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            line.append(c);
            width += c < 0x100 ? 6 : 12;
            if (width >= maxPx) {
                out.add(line.toString());
                line.setLength(0);
                width = 0;
            }
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
