package com.breakfront.client.ui;

import com.breakfront.client.bf.BfServerConfig;
import com.breakfront.client.upd.MusicUpdater;
import com.breakfront.client.upd.Updater;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 启动预检屏（2026-09-05）：进入主菜单前完成「模组更新检查/下载 + 外置音频同步」，
 * 以进度条形式呈现（BF 风格深色加载屏）。有更新需要重启时展示「应用更新并重启」卡片
 * （armAutoApply 影子脚本自动替换 jar 并重新拉起游戏，全程无手动操作）。
 */
public class BfBootstrapScreen extends Screen {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bf-bootstrap");
        t.setDaemon(true);
        return t;
    });

    // 渲染线程读取的进度状态
    private volatile String stageText = "正在初始化…";
    private volatile float frac = -1f;        // -1 = 不确定态
    private volatile boolean finished;
    private volatile boolean needRestart;
    private volatile long restartAtMs = Long.MAX_VALUE;
    private volatile float age;

    public BfBootstrapScreen() {
        super(Text.literal("BREAKFRONT 启动预检"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        // 预检完成前不允许 ESC 跳过
        if (finished) {
            super.close();
        }
    }

    @Override
    public void init() {
        IO.execute(this::bootstrap);
    }

    private void bootstrap() {
        String host = BfServerConfig.host();
        int updPort = BfServerConfig.updatePort();
        Updater.Progress prog = (s, f) -> {
            stageText = s;
            frac = f;
        };
        try {
            // 阶段 1：模组更新
            Updater.Result ur = Updater.runWithProgress(host, updPort, prog);
            if (ur.outcome() == Updater.Outcome.UPDATED_REQUIRES_RESTART) {
                stageText = "新版本模组已就绪，正在应用并自动重启…";
                frac = 1f;
                needRestart = true;
                restartAtMs = System.currentTimeMillis() + 6000;
                return;
            }
            // 阶段 2：音频库同步
            stageText = "正在检查音频库…";
            frac = 0.02f;
            MusicUpdater.sync(host, updPort, prog);
        } catch (Exception e) {
            stageText = "预检异常（不影响连接）：" + e;
        }
        finished = true;
        // 切主菜单（渲染线程）
        if (this.client != null) {
            this.client.execute(() -> this.client.setScreen(new BreakfrontMainMenu()));
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (needRestart) {
            int cw = Math.min(this.width - 80, 460);
            int ch = 150;
            int x = this.width / 2 - cw / 2;
            int y = this.height / 2 - ch / 2;
            int bW = 150;
            int bH = 32;
            int by = y + ch - bH - 16;
            if (mx >= x + cw / 2 - bW / 2 && mx <= x + cw / 2 + bW / 2
                    && my >= by && my <= by + bH) {
                applyRestart();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private void applyRestart() {
        if (this.client == null) {
            return;
        }
        boolean armed = Updater.armAutoApply();
        this.client.scheduleStop(); // 影子脚本在退出后替换 jar 并以原命令行重启
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        age += delta;
        int w = this.width;
        int h = this.height;
        ctx.fill(0, 0, w, h, 0xFF070A0E);

        int cx = w / 2;
        int baseY = h / 2 - 20;
        // 菱形 logo
        float pulse = 0.5f + 0.5f * (float) Math.sin(age * 0.05);
        RenderSystem.enableBlend();
        ctx.fill(cx - 8, baseY - 90 - 8, cx - 6, baseY - 90 + 8, 0xFFf5cd54);
        ctx.fill(cx - 8, baseY - 90 - 8, cx + 8, baseY - 90 - 6, 0xFFf5cd54);
        ctx.fill(cx + 6, baseY - 90 - 8, cx + 8, baseY - 90 + 8, 0xFFf5cd54);
        ctx.fill(cx - 8, baseY - 90 + 6, cx + 8, baseY - 90 + 8, 0xFFf5cd54);

        String title = "BREAKFRONT · 破阵前线";
        int tw = this.textRenderer.getWidth(title);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(title), cx, baseY - 76, 0xFFdfe6ee);

        // 阶段文本
        String st = stageText;
        int stw = this.textRenderer.getWidth(st);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(st), cx, baseY + 4, 0xFF7c8a99);

        // 进度条
        int barW = Math.min(420, w - 120);
        int barH = 10;
        int bx = cx - barW / 2;
        int by = baseY + 32;
        ctx.fill(bx, by, bx + barW, by + barH, 0xFF141b24);
        if (frac >= 0f) {
            int fw = (int) (barW * Math.min(1f, Math.max(0f, frac)));
            if (fw > 0) {
                ctx.fill(bx, by, bx + fw, by + barH, 0xFFf5cd54);
            }
            String pct = String.format("%d%%", (int) (Math.min(1f, Math.max(0f, frac)) * 100));
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(pct), cx, by + barH + 6, 0xFF7c8a99);
        } else {
            // 不确定态：平移黄块
            int pos = (int) ((age * 40) % (barW - 60));
            ctx.fill(bx + pos, by, bx + pos + 60, by + barH, 0xFFf5cd54);
        }

        // 重启卡片
        if (needRestart) {
            ctx.fill(0, 0, w, h, 0xA005070A);
            int cw = Math.min(w - 80, 460);
            int ch = 150;
            int x = cx - cw / 2;
            int y = h / 2 - ch / 2;
            ctx.fill(x, y, x + cw, y + ch, 0xFF141b24);
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("更新完成，需要重启生效"), cx, y + 26, 0xFFf5cd54);
            long left = Math.max(0, (restartAtMs - System.currentTimeMillis()) / 1000);
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("将自动重启（" + left + "s）…也可点下方按钮立即应用"), cx, y + 52, 0xFF7c8a99);
            int bW = 150;
            int bH = 32;
            int by2 = y + ch - bH - 16;
            boolean hover = mouseX >= cx - bW / 2 && mouseX <= cx + bW / 2
                    && mouseY >= by2 && mouseY <= by2 + bH;
            ctx.fill(cx - bW / 2, by2, cx + bW / 2, by2 + bH,
                    hover ? 0xFFFFFFFF : 0xFFf5cd54);
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("立即重启"), cx, by2 + 9, 0xFF0a0d12);
            if (System.currentTimeMillis() >= restartAtMs) {
                applyRestart();
            }
        }
    }
}
