package com.breakfront.client.ui;

import com.breakfront.client.bf.BfDraw;
import com.breakfront.client.bf.BfEasing;
import com.breakfront.client.bf.BfTheme;
import com.breakfront.client.state.ClientMatchState;
import com.breakfront.client.state.ClientMatchState.ZoneView;
import com.breakfront.game.Side;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.List;

/**
 * 部署界面（BF2042 Deploy 结构）。回合进入 COUNTDOWN 时自动弹出：
 *
 * 左侧：战区模式头 + 待部署据点列表（字母菱形 + 状态/推进条 + 坐标）
 * 中央：倒计时大屏与文案
 * 底部：提示条 —— 战斗中自动关闭 / ESC 可提前返回战场
 *
 * 当前为展示层 v0.5（重生/兵种选择待服务端规则就绪后接入），
 * 由 BreakfrontClient 在 phase 变化时自动开合。
 */
public class BfDeployScreen extends Screen {

    private double age;

    public BfDeployScreen() {
        super(Text.literal("DEPLOYMENT"));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        this.age += delta;
        int sw = this.width;
        int sh = this.height;

        // 深色全屏 + 对角装饰
        BfDraw.gradientV(ctx, 0, 0, sw, sh, 0xFF0A0D12, 0xFF141B26);
        BfDraw.parallelogram(ctx, -120, sh - 190, sw / 2, 5, 60, 0x14FFFFFF);
        BfDraw.parallelogram(ctx, sw / 3, -30, sw / 3, 4, -40, 0x0FFFFFFF);
        BfDraw.fill(ctx, 0, 0, 4, sh, BfTheme.YELLOW);

        double in = BfEasing.staged(age, 0.05, 0.5);
        int a = (int) (255 * in);
        int rise = (int) ((1 - in) * 20);

        // 标题区
        int pad = Math.max(30, sw / 22);
        int ty = pad + rise;
        ctx.drawText(this.textRenderer, Text.literal("DEPLOYMENT  部署"),
                pad, ty, argb(BfTheme.YELLOW, a), false);
        ctx.drawText(this.textRenderer, Text.literal("ALL-OUT WARFARE  ·  全面战争"),
                pad, ty + 13, argb(BfTheme.MUTED, a), false);

        // 倒计时大数（中央偏上）
        String cd = String.format("%.0f", Math.max(0, ClientMatchState.countdownRemainingSeconds()));
        int cdW = this.textRenderer.getWidth(cd);
        int cx = sw / 2 - cdW / 2;
        ctx.drawText(this.textRenderer, Text.literal(cd), cx, sh / 2 - 64,
                argb(0xFFF5D44A, a), false);
        String lbl = "开战倒计时";
        int lw = this.textRenderer.getWidth(lbl);
        ctx.drawText(this.textRenderer, Text.literal(lbl), sw / 2 - lw / 2, sh / 2 - 40,
                argb(BfTheme.MUTED, a), false);

        // 部署据点面板（左下区块）
        List<ZoneView> zones = ClientMatchState.zones();
        int panelW = (int) Math.min(sw * 0.46, 380);
        int panelX = pad;
        int panelTop = ty + 64;
        int rowH = 34;
        int panelH = Math.max(90, zones.size() * rowH + 22);
        BfDraw.fill(ctx, panelX, panelTop, panelW, panelH, argb(BfTheme.PANEL, a));
        BfDraw.border(ctx, panelX, panelTop, panelW, panelH, argb(BfTheme.PANEL_LINE, a));
        ctx.drawText(this.textRenderer, Text.literal("目标点  SECTOR " + (ClientMatchState.sectorIndex() + 1)),
                panelX + 14, panelTop + 9, argb(BfTheme.TEXT_DIM, a), false);

        int rowY = panelTop + 24;
        for (int i = 0; i < zones.size(); i++) {
            ZoneView z = zones.get(i);
            int ry = rowY + i * rowH;
            Side owner = Side.values()[z.ownerOrdinal()];
            // 菱形字母
            BfDraw.diamond(ctx, panelX + 16, ry + 12, 6,
                    owner == Side.ATTACKER ? BfTheme.YELLOW : BfTheme.BLUE);
            ctx.drawText(this.textRenderer, Text.literal(z.letter()),
                    panelX + 12, ry + 7, 0xFF0A0D12, false);
            String state = owner == Side.ATTACKER
                    ? "已占领"
                    : (z.meter() > 1e-3f ? "争夺中 " + (int) (z.meter() * 100) + "%" : "防守中");
            ctx.drawText(this.textRenderer, Text.literal(state),
                    panelX + 32, ry + 8, argb(owner == Side.ATTACKER ? BfTheme.YELLOW : BfTheme.TEXT_DIM, a), false);
            String coord = String.format("(%.0f, %.0f)", z.worldX(), z.worldZ());
            int cw2 = this.textRenderer.getWidth(coord);
            ctx.drawText(this.textRenderer, Text.literal(coord),
                    panelX + panelW - cw2 - 14, ry + 8, argb(BfTheme.FAINT, a), false);
            // 底部细进度（争夺时）
            if (z.meter() > 1e-3f && owner == Side.DEFENDER) {
                ctx.fill(panelX + 14, ry + 24, panelX + panelW - 14, ry + 25, 0x33FFFFFF);
                ctx.fill(panelX + 14, ry + 24,
                        panelX + 14 + (int) ((panelW - 28) * Math.min(1, z.meter())),
                        ry + 25, BfTheme.YELLOW);
            }
        }

        // 底部提示
        String hint = "开战后自动关闭 · ESC 可提前返回战场 · 部署重生系统开发中";
        int hw = this.textRenderer.getWidth(hint);
        ctx.drawText(this.textRenderer, Text.literal(hint), sw / 2 - hw / 2, sh - 24,
                argb(BfTheme.FAINT, a), false);
    }

    private static int argb(int rgb, int alpha) {
        int aa = Math.max(0, Math.min(255, alpha));
        return (aa << 24) | (rgb & 0xFFFFFF);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
