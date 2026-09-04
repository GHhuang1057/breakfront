package com.breakfront.client.ui;

import com.breakfront.client.bf.BfDraw;
import com.breakfront.client.bf.BfEasing;
import com.breakfront.client.bf.BfTheme;
import com.breakfront.client.state.ClientMatchState;
import com.breakfront.client.state.ClientMatchState.ZoneView;
import com.breakfront.game.Side;
import com.breakfront.net.SetClassPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.List;

/**
 * 部署界面（BF2042 Deploy 结构）：
 *
 * - COUNTDOWN 模式（每局开战自动弹）：左侧目标点列表、中央倒计时、右侧 4 兵种卡（点击选择并上报服务端）
 * - RESPAWN 模式（战斗中死亡）：全屏「已阵亡」+ 大部署按钮（点击 vanilla respawn，重生点已在己方防线）
 *
 * 全矢量绘制。由 BreakfrontClient 按 phase 自动开合。
 */
public class BfDeployScreen extends Screen {

    private static final String[][] CLASSES = {
            {"assault", "突击兵", "ASSAULT", "前线攻坚 · 推进占点"},
            {"engineer", "工程兵", "ENGINEER", "反载具 · 火箭筒"},
            {"support", "支援兵", "SUPPORT", "弹药补给 · 压制"},
            {"recon", "侦察兵", "RECON", "索敌标点 · 精确"},
    };

    private final boolean respawnMode;
    private double age;
    private int hoverClass = -1;
    private int selected = 0; // 本地当前选择（默认突击兵）

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

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        this.age += delta;
        int sw = this.width;
        int sh = this.height;

        BfDraw.gradientV(ctx, 0, 0, sw, sh, 0xFF0A0D12, 0xFF141B26);
        BfDraw.parallelogram(ctx, -120, sh - 190, sw / 2, 5, 60, 0x14FFFFFF);
        BfDraw.parallelogram(ctx, sw / 3, -30, sw / 3, 4, -40, 0x0FFFFFFF);
        BfDraw.fill(ctx, 0, 0, 4, sh, BfTheme.YELLOW);

        double in = BfEasing.staged(age, 0.05, 0.5);
        int a = (int) (255 * in);
        int rise = (int) ((1 - in) * 20);
        int pad = Math.max(30, sw / 22);
        int ty = pad + rise;

        if (respawnMode) {
            renderRespawn(ctx, mouseX, mouseY, sw, sh, pad, ty, a);
            return;
        }

        // ---- COUNTDOWN 部署模式 ----
        ctx.drawText(this.textRenderer, Text.literal("DEPLOYMENT  部署"),
                pad, ty, argb(BfTheme.YELLOW, a), false);
        ctx.drawText(this.textRenderer, Text.literal("ALL-OUT WARFARE  ·  全面战争"),
                pad, ty + 13, argb(BfTheme.MUTED, a), false);

        // 中央倒计时
        String cd = String.format("%.0f", Math.max(0, ClientMatchState.countdownRemainingSeconds()));
        int cdW = this.textRenderer.getWidth(cd);
        ctx.drawText(this.textRenderer, Text.literal(cd), sw / 2 - cdW / 2, sh / 2 - 70,
                argb(0xFFF5D44A, a), false);
        String lbl = "开战倒计时";
        int lw = this.textRenderer.getWidth(lbl);
        ctx.drawText(this.textRenderer, Text.literal(lbl), sw / 2 - lw / 2, sh / 2 - 46,
                argb(BfTheme.MUTED, a), false);

        // 在线人数小条
        String online = String.format("攻 %d  /  守 %d", ClientMatchState.attackerOnline(),
                ClientMatchState.defenderOnline());
        int ow = this.textRenderer.getWidth(online);
        ctx.drawText(this.textRenderer, Text.literal(online), sw / 2 - ow / 2, sh / 2 - 24,
                argb(BfTheme.FAINT, a), false);

        // 左：目标点列表
        List<ZoneView> zones = ClientMatchState.zones();
        int panelW = (int) Math.min(sw * 0.4, 340);
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
            BfDraw.diamond(ctx, panelX + 16, ry + 12, 6,
                    owner == Side.ATTACKER ? BfTheme.YELLOW : BfTheme.BLUE);
            ctx.drawText(this.textRenderer, Text.literal(z.letter()),
                    panelX + 12, ry + 7, 0xFF0A0D12, false);
            String state = owner == Side.ATTACKER
                    ? "已占领"
                    : (z.meter() > 1e-3f ? "争夺中 " + (int) (z.meter() * 100) + "%" : "防守中");
            ctx.drawText(this.textRenderer, Text.literal(state), panelX + 32, ry + 8,
                    argb(owner == Side.ATTACKER ? BfTheme.YELLOW : BfTheme.TEXT_DIM, a), false);
            String coord = String.format("(%.0f, %.0f)", z.worldX(), z.worldZ());
            int cw2 = this.textRenderer.getWidth(coord);
            ctx.drawText(this.textRenderer, Text.literal(coord),
                    panelX + panelW - cw2 - 14, ry + 8, argb(BfTheme.FAINT, a), false);
            if (z.meter() > 1e-3f && owner == Side.DEFENDER) {
                ctx.fill(panelX + 14, ry + 24, panelX + panelW - 14, ry + 25, 0x33FFFFFF);
                ctx.fill(panelX + 14, ry + 24,
                        panelX + 14 + (int) ((panelW - 28) * Math.min(1, z.meter())),
                        ry + 25, BfTheme.YELLOW);
            }
        }

        // 右：兵种卡
        int clsW = (int) Math.min(sw * 0.34, 300);
        int clsX = sw - pad - clsW;
        int clsTop = ty + 64;
        int cardH = 58;
        int gap = 8;
        ctx.drawText(this.textRenderer, Text.literal("选择兵种  ·  SELECT CLASS"),
                clsX, ty + 40, argb(BfTheme.TEXT_DIM, a), false);
        for (int i = 0; i < CLASSES.length; i++) {
            int cy = clsTop + i * (cardH + gap);
            int ry2 = cy + cardH;
            boolean hov = mouseX >= clsX && mouseX <= clsX + clsW && mouseY >= cy && mouseY <= ry2;
            boolean sel = i == selected;
            int cardCol = argb(sel ? 0xE6303E50 : BfTheme.PANEL, a);
            BfDraw.fill(ctx, clsX, cy, clsW, cardH, cardCol);
            int edge = sel ? BfTheme.YELLOW : (hov ? argb(BfTheme.TEXT_DIM, a) : argb(BfTheme.PANEL_LINE, a));
            BfDraw.border(ctx, clsX, cy, clsW, cardH, edge);
            if (sel) {
                BfDraw.fill(ctx, clsX, cy, 3, cardH, BfTheme.YELLOW);
            }
            ctx.drawText(this.textRenderer, Text.literal(CLASSES[i][2]),
                    clsX + 14, cy + 9, argb(sel ? BfTheme.YELLOW : BfTheme.TEXT, a), false);
            ctx.drawText(this.textRenderer, Text.literal(CLASSES[i][3]),
                    clsX + 14, cy + 24, argb(BfTheme.MUTED, a), false);
            String cn = CLASSES[i][1];
            int cnW = this.textRenderer.getWidth(cn);
            ctx.drawText(this.textRenderer, Text.literal(cn),
                    clsX + clsW - cnW - 12, cy + 8, argb(BfTheme.FAINT, a), false);
            if (sel) {
                BfDraw.fill(ctx, clsX + clsW - 26, cy + cardH / 2 - 6, 14, 12, BfTheme.YELLOW);
                ctx.drawText(this.textRenderer, Text.literal("✓"),
                        clsX + clsW - 23, cy + cardH / 2 - 5, 0xFF0A0D12, false);
            }
        }
        hoverClass = hovClass(mouseX, mouseY, clsX, clsTop, clsW, cardH, gap);

        String hint = "开战后自动关闭 · ESC 可提前返回战场";
        int hw = this.textRenderer.getWidth(hint);
        ctx.drawText(this.textRenderer, Text.literal(hint), sw / 2 - hw / 2, sh - 24,
                argb(BfTheme.FAINT, a), false);
    }

    // ---- RESPAWN 模式：阵亡部署 ----

    private void renderRespawn(DrawContext ctx, int mx, int my, int sw, int sh,
                               int pad, int ty, int a) {
        ctx.drawText(this.textRenderer, Text.literal("K.I.A.  你已阵亡"),
                pad, ty, argb(BfTheme.RED, a), false);
        ctx.drawText(this.textRenderer, Text.literal("部署到己方防线继续作战"),
                pad, ty + 14, argb(BfTheme.MUTED, a), false);

        // 中央大部署按钮
        int bw = Math.min(sw - 120, 360);
        int bh = 46;
        int bx = sw / 2 - bw / 2;
        int by = (int) (sh * 0.62);
        boolean hov = mx >= bx && mx <= bx + bw && my >= by && my <= by + bh;
        BfDraw.fill(ctx, bx, by, bw, bh, hov ? 0xFF0A0D12 : BfTheme.YELLOW);
        BfDraw.border(ctx, bx, by, bw, bh, hov ? BfTheme.YELLOW : BfTheme.YELLOW_DIM);
        int tCol = hov ? BfTheme.YELLOW : 0xFF0A0D12;
        String txt = "部署  DEPLOY";
        int tw = this.textRenderer.getWidth(txt);
        ctx.drawText(this.textRenderer, Text.literal(txt), sw / 2 - tw / 2, by + 15, tCol, false);
        // 按钮下方副提示
        String sub = "重生点已锁定在己方部署区";
        int sw2 = this.textRenderer.getWidth(sub);
        ctx.drawText(this.textRenderer, Text.literal(sub), sw / 2 - sw2 / 2, by + bh + 10,
                argb(BfTheme.FAINT, a), false);
        ctx.fill(bx, by + bh + 22, bx + bw, by + bh + 24, argb(BfTheme.PANEL_LINE, a));
        String stats = "本局击杀 " + myKills() + "  ·  爆头 " + myHeadshots();
        int stw = this.textRenderer.getWidth(stats);
        ctx.drawText(this.textRenderer, Text.literal(stats), sw / 2 - stw / 2, by + bh + 30,
                argb(BfTheme.TEXT_DIM, a), false);
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

    private int hovClass(int mx, int my, int x, int top, int w, int h, int gap) {
        for (int i = 0; i < CLASSES.length; i++) {
            int cy = top + i * (h + gap);
            if (mx >= x && mx <= x + w && my >= cy && my <= cy + h) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (respawnMode) {
            int sw = this.width;
            int sh = this.height;
            int bw = Math.min(sw - 120, 360);
            int bh = 46;
            int bx = sw / 2 - bw / 2;
            int by = (int) (sh * 0.62);
            if (mouseX >= bx && mouseX <= bx + bw && mouseY >= by && mouseY <= by + bh) {
                if (this.client != null && this.client.player != null) {
                    this.client.player.requestRespawn();
                    this.client.setScreen(null);
                }
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (hoverClass >= 0 && hoverClass < CLASSES.length) {
            selected = hoverClass;
            if (this.client != null) {
                ClientPlayNetworking.send(new SetClassPayload(CLASSES[hoverClass][0]));
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
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
        return !respawnMode; // 阵亡部署必须点击部署，不能 ESC 逃避
    }
}
