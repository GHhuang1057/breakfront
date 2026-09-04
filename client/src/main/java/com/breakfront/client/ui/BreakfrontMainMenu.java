package com.breakfront.client.ui;

import com.breakfront.client.bf.BfDraw;
import com.breakfront.client.bf.BfEasing;
import com.breakfront.client.bf.BfServerConfig;
import com.breakfront.client.bf.BfTheme;
import com.breakfront.client.upd.Updater;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.network.CookieStorage;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BREAKFRONT 主菜单（BF2042 精神：无服务器选择、一键 PLAY 直连）。
 *
 * - 深色矢量布局：金橙主行动按钮、次级幽灵按钮、品牌色带与几何装饰，全部程序化绘制
 * - 交互动画：进场面(浮入/淡入)、悬停平滑补间、按钮滑入高光；连接阶段全屏呼吸提示
 * - PLAY 流程：先查服务端模组更新源 → 版本一致或源不可达则直连；发现更新则提示重启
 * - 服务器地址硬编码自 BfServerConfig（上线后单点替换域名）
 */
public class BreakfrontMainMenu extends Screen {

    private enum Flow { IDLE, CHECKING, CONNECTING, NEED_RESTART }

    // 布局
    private int brandX;
    private int brandTop;
    private int actX;
    private int actTop;
    private int btnW;
    private final int btnH = 48;
    private final int smallBtnW = 132;
    private final int smallBtnH = 34;
    private final int btnGap = 14;

    // 运行态
    private double age;
    private Flow flow = Flow.IDLE;
    private String flowMsg = "";
    private final List<String> noticeLines = new ArrayList<>();
    private final ExecutorService ioPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "breakfront-updater");
        t.setDaemon(true);
        return t;
    });

    private float playHover;   // 0..1

    public BreakfrontMainMenu() {
        super(Text.literal("BREAKFRONT"));
    }

    @Override
    protected void init() {
        brandX = (int) (width * 0.10);
        brandTop = (int) (height * 0.18);
        btnW = (int) Math.max(240, Math.min(420, width * 0.30));
        actX = width - (int) (width * 0.10) - btnW;
        actTop = (int) (height * 0.40);
        playHover = 0;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        this.age += delta;
        int sw = this.width;
        int sh = this.height;

        // ---- 背景 ----
        BfDraw.gradientV(ctx, 0, 0, sw, sh, BfTheme.BG_DEEP, BfTheme.BG_UP);
        // 装饰：低速漂移的斜向色带（BF 风格的战斗层次感）
        double drift = Math.sin(age * 0.06) * 8.0;
        BfDraw.parallelogram(ctx, (int) (-80 + drift), sh - 210, sw / 2, 4, 40, 0x14FFFFFF);
        BfDraw.parallelogram(ctx, sw / 2 - 120, (int) (drift), sw / 3, 3, -30, 0x0FFFFFFF);
        BfDraw.parallelogram(ctx, -60, 90, sw / 4, 2, 26, 0x10FFFFFF);
        // 左侧竖色带（品牌锚）
        BfDraw.fill(ctx, 0, 0, 4, sh, BfTheme.ORANGE);
        BfDraw.fill(ctx, 4, 0, 2, sh, 0x66E8B93C);

        // ---- 左品牌区 ----
        double pTitle = BfEasing.staged(age, 0.05, 0.5);
        int ty = (int) (brandTop + (1 - pTitle) * 26);
        drawBrand(ctx, brandX, ty, pTitle);
        drawStatusFooter(ctx, sh);

        // ---- 中下行动区 ----
        if (flow == Flow.IDLE) {
            drawActions(ctx, mouseX, mouseY, delta);
        } else if (flow == Flow.NEED_RESTART) {
            drawRestartCard(ctx, sw, sh);
        } else {
            drawConnecting(ctx, sw, sh, delta);
        }
    }

    private void drawBrand(DrawContext ctx, int x, int y, double alpha) {
        int a = (int) (255 * alpha);
        TextRenderer font = this.textRenderer;
        if (a <= 0) {
            return;
        }
        // 字距标题
        String word = "B R E A K F R O N T";
        int w0 = font.getWidth(word);
        ctx.drawText(font, Text.literal(word), x, y, 0xFFFAFCFF, false);
        // 副标
        ctx.drawText(font, Text.literal("破阵前线 · 大战场对战"), x + 2, y + 14, BfTheme.MUTED, false);
        // 强调条（斜切色带）
        BfDraw.parallelogram(ctx, x, y + 34, 130, 5, 12, BfTheme.ORANGE);
        BfDraw.fill(ctx, x + 140, y + 34, 34, 5, BfTheme.AMBER);
        // 几何徽章：菱形 + 斜切方块组合
        BfDraw.diamond(ctx, x + 196, y + 37, 8, 0xFFE8B93C);
        BfDraw.parallelogram(ctx, x + 214, y + 28, 22, 18, 6, 0x3DE8622C);
    }

    private void drawActions(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // 标题行
        ctx.drawText(this.textRenderer, Text.literal("立即加入战场"), actX, actTop - 26, BfTheme.TEXT_DIM, false);

        // PLAY 主按钮
        playHover = smooth(playHover, hover(actX, actTop, btnW, btnH, mouseX, mouseY), delta);
        drawPlayButton(ctx);

        // 次级
        int sy = actTop + btnH + btnGap;
        float[] hs = secondaryHover(mouseX, mouseY);
        drawGhost(ctx, "单机", actX, sy, hs[0]);
        drawGhost(ctx, "选项", actX + smallBtnW + 10, sy, hs[1]);
        drawGhost(ctx, "退出", actX + 2 * (smallBtnW + 10), sy, hs[2]);
    }

    private float[] secondaryHover(int mx, int my) {
        int sy = actTop + btnH + btnGap;
        float[] hs = new float[3];
        hs[0] = hover(actX, sy, smallBtnW, smallBtnH, mx, my);
        hs[1] = hover(actX + smallBtnW + 10, sy, smallBtnW, smallBtnH, mx, my);
        hs[2] = hover(actX + 2 * (smallBtnW + 10), sy, smallBtnW, smallBtnH, mx, my);
        return hs;
    }

    private void drawPlayButton(DrawContext ctx) {
        int x = actX;
        int y = actTop;
        double lift = BfEasing.staged(age, 0.15, 0.5);
        int yy = (int) (y - (1 - lift) * 12);
        int alpha = (int) (255 * lift);
        // 高光渐变由 hover 驱动
        double h = BfEasing.easeOutCubic(playHover);
        // 主色块（斜切平行四边形）
        int base = argbMul(BfTheme.ORANGE, alpha);
        BfDraw.parallelogram(ctx, x, yy, btnW, btnH, 8, base);
        // hover：左缘亮条 + 底边过渡光
        if (h > 0.02) {
            int glow = argbMul(0xFFFFFFFF, (int) (180 * h * alpha / 255));
            BfDraw.parallelogram(ctx, x, yy, 7, btnH, 3, glow);
            BfDraw.fill(ctx, x + 6, yy + btnH - 3, btnW - 4, 3, argbMul(0xFFF3C66B, (int) (alpha)));
        }
        // 文字 + hover 平移
        int tx = x + (int) (h * 6);
        TextRenderer font = this.textRenderer;
        String label = "P L A Y";
        int lw = font.getWidth(label);
        ctx.drawText(font, Text.literal(label), tx + btnW / 2 - lw / 2,
                yy + btnH / 2 - font.fontHeight / 2, 0xFFFFFFFF, false);
        // 底部小字：直连地址
        ctx.drawText(font, Text.literal("直连 " + BfServerConfig.address()),
                actX + 2, yy + btnH + 8, BfTheme.FAINT, false);
    }

    private void drawGhost(DrawContext ctx, String label, int x, int y, float hover) {
        double h = BfEasing.easeOutCubic(hover);
        int border = h > 0.5 ? BfTheme.TEXT : BfTheme.PANEL_LINE;
        BfDraw.fill(ctx, x, y, smallBtnW, smallBtnH, 0x66161C25);
        BfDraw.fill(ctx, x, y, smallBtnW, 2, border);
        int col = h > 0.5 ? 0xFFFFFFFF : BfTheme.TEXT_DIM;
        int lw = textRenderer.getWidth(label);
        int shift = (int) (h * 3);
        ctx.drawText(textRenderer, Text.literal(label), x + smallBtnW / 2 - lw / 2 + shift,
                y + smallBtnH / 2 - textRenderer.fontHeight / 2, col, false);
    }

    private void drawConnecting(DrawContext ctx, int sw, int sh, float delta) {
        BfDraw.fill(ctx, 0, 0, sw, sh, BfTheme.SCREEN_DIM);
        double pulse = (Math.sin(age * 5.0) + 1) / 2;
        // 呼吸菱形
        double cx = sw / 2.0;
        double cy = sh / 2.0 - 40;
        double sz = 16 + pulse * 8;
        int ring = argbMul(0xFFE8B93C, (int) (120 + 135 * pulse));
        BfDraw.diamond(ctx, cx, cy, sz, ring);
        BfDraw.diamond(ctx, cx, cy, sz * 0.45, BfTheme.ORANGE);
        // 文案
        int lw = textRenderer.getWidth(flowMsg);
        ctx.drawText(textRenderer, Text.literal(flowMsg), (int) (cx - lw / 2.0),
                (int) (cy + 44), BfTheme.TEXT_DIM, false);
    }

    private void drawRestartCard(DrawContext ctx, int sw, int sh) {
        BfDraw.fill(ctx, 0, 0, sw, sh, BfTheme.SCREEN_DIM);
        int cw = (int) Math.min(sw * 0.72, 520);
        int ch = 40 + noticeLines.size() * 13 + 70;
        int x = sw / 2 - cw / 2;
        int y = sh / 2 - ch / 2;
        BfDraw.fill(ctx, x, y, cw, ch, BfTheme.PANEL);
        BfDraw.fill(ctx, x, y, 4, ch, BfTheme.AMBER);
        ctx.drawText(textRenderer, Text.literal("需要重启以应用更新"), x + 26, y + 18, 0xFFFFFFFF, false);
        int ly = y + 42;
        for (String line : noticeLines) {
            ctx.drawText(textRenderer, Text.literal(line), x + 26, ly, BfTheme.TEXT_DIM, false);
            ly += 13;
        }
        // 按钮：返回
        int bW = 150;
        int bH = 34;
        int bx = x + cw - bW - 22;
        int by = y + ch - bH - 16;
        drawSmallPrimary(ctx, "返回", bx, by, bW, bH);
    }

    private void drawSmallPrimary(DrawContext ctx, String label, int x, int y, int w, int h) {
        BfDraw.parallelogram(ctx, x, y, w, h, 6, BfTheme.ORANGE);
        int lw = textRenderer.getWidth(label);
        ctx.drawText(textRenderer, Text.literal(label), x + w / 2 - lw / 2,
                y + h / 2 - textRenderer.fontHeight / 2, 0xFFFFFFFF, false);
    }

    private void drawStatusFooter(DrawContext ctx, int sh) {
        String v = FabricLoader.getInstance().getModContainer("breakfront-client")
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
        ctx.drawText(textRenderer, Text.literal("BREAKFRONT v" + v),
                14, sh - 22, BfTheme.FAINT, false);
        String right = "服务器 " + BfServerConfig.address() + "  ·  更新源 :" + BfServerConfig.updatePort();
        int rw = textRenderer.getWidth(right);
        ctx.drawText(textRenderer, Text.literal(right), width - rw - 14, sh - 22, BfTheme.FAINT, false);
    }

    // ---------------- 交互 ----------------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) {
            return false;
        }
        if (flow == Flow.NEED_RESTART) {
            int cw = (int) Math.min(width * 0.72, 520);
            int ch = 40 + noticeLines.size() * 13 + 70;
            int x = width / 2 - cw / 2;
            int y = height / 2 - ch / 2;
            if (inRect(mx, my, x + cw - 150 - 22, y + ch - 34 - 16, 150, 34)) {
                flow = Flow.IDLE;
                return true;
            }
            return false;
        }
        if (flow != Flow.IDLE) {
            return false;
        }
        if (inRect(mx, my, actX, actTop, btnW, btnH)) {
            onPlay();
            return true;
        }
        int sy = actTop + btnH + btnGap;
        if (inRect(mx, my, actX, sy, smallBtnW, smallBtnH)) {
            client.setScreen(new SelectWorldScreen(this));
            return true;
        }
        if (inRect(mx, my, actX + smallBtnW + 10, sy, smallBtnW, smallBtnH)) {
            client.setScreen(new OptionsScreen(this, client.options));
            return true;
        }
        if (inRect(mx, my, actX + 2 * (smallBtnW + 10), sy, smallBtnW, smallBtnH)) {
            client.scheduleStop();
            return true;
        }
        return false;
    }

    private void onPlay() {
        flow = Flow.CHECKING;
        flowMsg = "正在校验模组版本…";
        String host = BfServerConfig.host();
        int updPort = BfServerConfig.updatePort();
        ioPool.execute(() -> {
            Updater.Result r = Updater.run(host, updPort);
            client.execute(() -> onUpdateResult(r));
        });
    }

    private void onUpdateResult(Updater.Result r) {
        if (r.proceedToConnect()) {
            startConnect();
            return;
        }
        flow = Flow.NEED_RESTART;
        wrap(noticeLines, r.message(), (int) Math.min(width * 0.72, 520) - 64);
    }

    private void startConnect() {
        flow = Flow.CONNECTING;
        flowMsg = "连接 " + BfServerConfig.address() + " …";
        try {
            String addr = BfServerConfig.address();
            ConnectScreen.connect(this, client, ServerAddress.parse(addr),
                    new ServerInfo("BREAKFRONT", addr, ServerInfo.ServerType.OTHER),
                    false, new CookieStorage(new HashMap<>()));
        } catch (Exception e) {
            flow = Flow.IDLE;
        }
    }

    // ---------------- 工具 ----------------

    private float smooth(float cur, float target, float delta) {
        float k = (float) Math.min(1, delta * 14);
        return cur + (target - cur) * k;
    }

    private boolean hover(int x, int y, int w, int h, double mx, double my) {
        return inRect(mx, my, x, y, w, h);
    }

    private static boolean inRect(double mx, double my, double x, double y, double w, double h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static int argbMul(int argb, int mulA) {
        int a = ((argb >>> 24) & 0xFF) * mulA / 255;
        return (a << 24) | (argb & 0xFFFFFF);
    }

    private static void wrap(List<String> out, String text, int maxPx) {
        out.clear();
        // 简易折行：按视觉宽度逐字符累积
        StringBuilder line = new StringBuilder();
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            line.append(c);
            if (c < 0x100) {
                width += 6;
            } else {
                width += 12;
            }
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
