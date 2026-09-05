package com.breakfront.client.hud;

import com.breakfront.client.bf.BfDraw;
import com.breakfront.client.bf.BfTheme;
import com.breakfront.client.state.ClientMatchState;
import com.breakfront.client.state.ClientMatchState.KillEvent;
import com.breakfront.client.state.ClientMatchState.ZoneView;
import com.breakfront.game.Side;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

import java.util.List;

/**
 * 战场 HUD v2 —— BF2042 排版语言：
 *
 * 左上 OBJECTIVE：据点字母胶囊（占领方着色/争夺呼吸）
 * 中上：扇区 x/y + 阶段
 * 右上：倒计时大数值 + 部署资源
 * 底部：当前扇区据点推进条
 * 击杀流：右上时钟下方，行淡入 + 轻微上滑 + 超时淡出
 * （渲染纪律：纯几何 + 文本，无贴图）
 */
public class BreakfrontHud {

    private static final String[] PHASE_LABELS = {
            "大厅", "部署", "战斗中", "结算", "重置"
    };

    private int seenPhase = -1;
    private long phaseEnterMs;

    public void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.options == null) {
            return;
        }
        TextRenderer font = client.textRenderer;
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();
        int phase = ClientMatchState.phaseOrdinal();
        if (phase != seenPhase) {
            seenPhase = phase;
            phaseEnterMs = System.currentTimeMillis();
        }

        // TAB 计分板：按住显示（优先于一切战场 HUD）
        if (isTabHeld(client) && (phase == 1 || phase == 2)) {
            renderScoreboard(context, font, sw, sh);
            return;
        }

        if (phase == 3) {
            renderRoundOver(context, font, sw, sh);
            return;
        }
        if (phase == 0) {
            renderLobby(context, font, sw, sh, client);
            return; // 大厅不渲染战场 HUD
        }
        if (phase != 1 && phase != 2) {
            return;
        }
        renderTopBar(context, font, sw);
        renderHealthWeapon(context, font, sw, sh);
        renderKillFeed(context, font, sw);
        renderHitMarkers(context, font, sw, sh);
        BfMinimap.render(context, font, sw, sh);
        ZoneMarkers.render(context, font, sw, sh);
        ActiveZonePin.render(context, font, sw); // #40：进入领地→顶栏钉卡（平滑转移）
    }

    // ---- W5：HitMarker 命中反馈（准星四角斜线） ----

    private void renderHitMarkers(DrawContext ctx, TextRenderer font, int sw, int sh) {
        long now = System.currentTimeMillis();
        List<ClientMatchState.HitEvent> marks = ClientMatchState.hitMarkers();
        if (marks.isEmpty()) {
            return;
        }
        int cx = sw / 2;
        int cy = sh / 2 - 2;
        // 仅在开镜/准星未隐藏场景也显示（BF 风格命中提示始终显示）
        for (ClientMatchState.HitEvent m : marks) {
            long ageMs = now - m.at();
            if (ageMs < 0 || ageMs > 420) {
                continue;
            }
            // 缩放出场 70ms → 停留 → 最后 120ms 淡出
            float in = Math.min(1f, ageMs / 70f);
            float out = Math.min(1f, Math.max(0f, (420 - ageMs) / 120f));
            float pop = 0.6f + 0.4f * in; // 0.6 → 1.0
            int alpha = (int) (220 * in * out);
            int base;
            int color;
            if (m.kind() >= 2) {
                base = 11; // 击杀/爆头：更大
                color = m.kind() == 3 ? BfTheme.GREEN : BfTheme.RED;
            } else {
                base = 7;
                color = 0xFFF2F4F8;
            }
            int r0 = (int) (base * pop);
            int r1 = r0 + 4;
            int c = argb(color, alpha);
            // 上
            BfDraw.fill(ctx, cx - 1, cy - r1, cx + 1, cy - r0, c);
            // 下
            BfDraw.fill(ctx, cx - 1, cy + r0, cx + 1, cy + r1, c);
            // 左 / 右
            BfDraw.fill(ctx, cx - r1, cy - 1, cx - r0, cy + 1, c);
            BfDraw.fill(ctx, cx + r0, cy - 1, cx + r1, cy + 1, c);
        }
    }

    private static boolean isTabHeld(MinecraftClient client) {
        try {
            return net.minecraft.client.util.InputUtil.isKeyPressed(
                    client.getWindow().getHandle(), org.lwjgl.glfw.GLFW.GLFW_KEY_TAB);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- W3：大厅「准备就绪」面板（phase 0） ----

    private void renderLobby(DrawContext ctx, TextRenderer font, int sw, int sh, MinecraftClient client) {
        BfDraw.fill(ctx, 0, 0, sw, sh, 0xBF06080C);
        BfDraw.gradientV(ctx, 0, (int) (sh * 0.72), sw, (int) (sh * 0.28), 0x00141B26, 0xFF141B26);
        BfDraw.parallelogram(ctx, -140, sh - 150, sw / 3, 5, 70, 0x16FFFFFF);
        BfDraw.fill(ctx, sw / 2 - 90, 0, 4, sh, BfTheme.GREEN);

        // 标题
        String head = "BREAKFRONT  ·  战备大厅";
        int hw = font.getWidth(head);
        int hy = (int) (sh * 0.30);
        ctx.drawText(font, Text.literal(head), sw / 2 - hw / 2, hy, 0xFFFFFFFF, false);
        String sub = "ALL-OUT WARFARE  ·  等待指挥官部署";
        int subW = font.getWidth(sub);
        ctx.drawText(font, Text.literal(sub), sw / 2 - subW / 2, hy + 16, BfTheme.GREEN_DIM, false);

        // 人数卡
        int cw = Math.min(360, sw - 80);
        int cx = sw / 2 - cw / 2;
        int cy = hy + 56;
        int chH = 86;
        BfDraw.fill(ctx, cx, cy, cw, chH, BfTheme.PANEL);
        BfDraw.border(ctx, cx, cy, cw, chH, BfTheme.PANEL_LINE);
        int half = cw / 2;
        String att = "进攻方  " + ClientMatchState.attackerOnline();
        String def = "防守方  " + ClientMatchState.defenderOnline();
        ctx.drawText(font, Text.literal(att), cx + 18, cy + 14, BfTheme.GREEN, false);
        ctx.drawText(font, Text.literal(def), cx + half + 18, cy + 14, BfTheme.BLUE, false);
        // 我方阵营（board 行匹配自身）
        String me = client.player != null ? client.player.getName().getString() : "";
        int mySide = -1;
        for (ClientMatchState.BoardRow r : ClientMatchState.board()) {
            if (r.name().equals(me)) {
                mySide = r.sideOrdinal();
                break;
            }
        }
        String sideText = mySide == 0 ? "进攻方" : (mySide == 1 ? "防守方" : "未分配");
        ctx.drawText(font, Text.literal("你的阵营  " + sideText), cx + 18, cy + 36,
                mySide == 0 ? BfTheme.GREEN : BfTheme.MUTED, false);
        ctx.drawText(font, Text.literal("指令  /bf team attacker|defender"), cx + half + 18, cy + 36,
                BfTheme.MUTED, false);
        ctx.drawText(font, Text.literal("分配后由管理员开局，或自动开局启用后满员即开"),
                cx + 18, cy + 58, BfTheme.FAINT, false);
    }

    // ---- W2：TAB 计分板（BF2042 排版：双队纵列 + 顶比分带） ----

    private void renderScoreboard(DrawContext ctx, TextRenderer font, int sw, int sh) {
        List<ClientMatchState.BoardRow> rows = ClientMatchState.board();
        List<ClientMatchState.BoardRow> att = new java.util.ArrayList<>();
        List<ClientMatchState.BoardRow> def = new java.util.ArrayList<>();
        for (ClientMatchState.BoardRow r : rows) {
            (r.sideOrdinal() == 0 ? att : def).add(r);
        }
        att.sort((a, b) -> b.kills() != a.kills() ? b.kills() - a.kills() : a.deaths() - b.deaths());
        def.sort((a, b) -> b.kills() != a.kills() ? b.kills() - a.kills() : a.deaths() - b.deaths());

        // 半透明深底
        BfDraw.fill(ctx, 0, 0, sw, sh, 0xB00A0D12);
        BfDraw.parallelogram(ctx, -160, sh - 220, sw / 3, 4, 70, 0x10FFFFFF);
        BfDraw.parallelogram(ctx, sw / 2, -40, sw / 3, 5, -55, 0x0FFFFFFF);

        // 顶比分带
        String score = String.format("%d    :    %d", ClientMatchState.attackerTeamKills(),
                ClientMatchState.defenderTeamKills());
        int scoreW = font.getWidth(score);
        ctx.drawText(font, Text.literal(score), sw / 2 - scoreW / 2, 24, 0xFFFFFFFF, false);
        String sub = "团队击杀  ·  回合阶段 " + PHASE_LABELS[Math.max(0, Math.min(ClientMatchState.phaseOrdinal(), 4))]
                + "  ·  扇区 " + (Math.min(ClientMatchState.sectorIndex() + 1, ClientMatchState.sectorCount()))
                + "/" + ClientMatchState.sectorCount();
        int subW = font.getWidth(sub);
        ctx.drawText(font, Text.literal(sub), sw / 2 - subW / 2, 40, BfTheme.FAINT, false);

        // 双列头
        int colW = (int) Math.min(sw * 0.42, 420);
        int gap = 24;
        int topY = 78;
        int headY = topY;
        int leftX = sw / 2 - colW - gap / 2;
        int rightX = sw / 2 + gap / 2;
        ctx.drawText(font, Text.literal("进攻方  ATTACKER"), leftX, headY, BfTheme.GREEN, false);
        ctx.drawText(font, Text.literal("防守方  DEFENDER"), rightX, headY, BfTheme.BLUE, false);
        // 列头（名称/击杀/死亡/爆头）
        int rowH = font.fontHeight + 7;
        drawHeader(ctx, font, leftX, headY + font.fontHeight + 4, colW);
        drawHeader(ctx, font, rightX, headY + font.fontHeight + 4, colW);

        int bodyTop = headY + font.fontHeight * 2 + 14;
        int maxRows = Math.max(att.size(), def.size());
        int bodyH = Math.max(1, maxRows) * rowH;
        int fullH = bodyTop + bodyH + 30;
        if (fullH > sh - 24) {
            return; // 放不下就只画头（极小窗口保护）
        }
        drawColumn(ctx, font, att, leftX, bodyTop, colW, rowH, BfTheme.GREEN_DIM);
        drawColumn(ctx, font, def, rightX, bodyTop, colW, rowH, BfTheme.BLUE);

        // 底提示
        String hint = "TAB 查看 · 进服后战绩自动记录 · K/D/爆头实时更新";
        int hw = font.getWidth(hint);
        ctx.drawText(font, Text.literal(hint), sw / 2 - hw / 2, sh - 22, BfTheme.FAINT, false);
    }

    private void drawHeader(DrawContext ctx, TextRenderer font, int x, int y, int colW) {
        ctx.drawText(font, Text.literal("玩家"), x, y, BfTheme.MUTED, false);
        int kx = x + colW - 66;
        ctx.drawText(font, Text.literal("击杀"), kx, y, BfTheme.MUTED, false);
        ctx.drawText(font, Text.literal("死亡"), kx + 30, y, BfTheme.MUTED, false);
        ctx.drawText(font, Text.literal("爆头"), kx + 60, y, BfTheme.MUTED, false);
    }

    private void drawColumn(DrawContext ctx, TextRenderer font,
                            List<ClientMatchState.BoardRow> list,
                            int x, int topY, int colW, int rowH, int accent) {
        int i = 0;
        for (ClientMatchState.BoardRow r : list) {
            int y = topY + i * rowH;
            if (i % 2 == 1) {
                ctx.fill(x - 4, y - 2, x + colW + 4, y + rowH - 2, 0x12FFFFFF);
            }
            ctx.fill(x - 4, y - 2, x + colW + 4, y - 1, accent & 0x33FFFFFF);
            ctx.drawText(font, Text.literal(r.name()), x, y, 0xFFFFFFFF, false);
            int kx = x + colW - 66;
            ctx.drawText(font, Text.literal(String.valueOf(r.kills())), kx, y, 0xFFFFFFFF, false);
            ctx.drawText(font, Text.literal(String.valueOf(r.deaths())), kx + 30, y,
                    r.deaths() == 0 ? BfTheme.FAINT : BfTheme.TEXT_DIM, false);
            ctx.drawText(font, Text.literal(String.valueOf(r.headshots())),
                    kx + 60, y, r.headshots() > 0 ? BfTheme.GREEN_DIM : BfTheme.FAINT, false);
            i++;
        }
    }

    // ---- C1：结算层（ROUND_END，含胜方标题 + 入场动效） ----

    private void renderRoundOver(DrawContext ctx, TextRenderer font, int sw, int sh) {
        com.breakfront.client.bf.BfDraw.fill(ctx, 0, 0, sw, sh, 0xCC05070A);
        long ageMs = System.currentTimeMillis() - phaseEnterMs;
        float t = Math.min(1f, Math.max(0f, ageMs / 340f));
        float in = (float) com.breakfront.client.bf.BfEasing.easeOutCubic(t);
        int a = (int) (255 * in);
        int rise = (int) ((1 - in) * 16);

        int cw = Math.min(sw - 60, 460);
        int ch = 176;
        int x = sw / 2 - cw / 2;
        int y = sh / 2 - ch / 2 + rise;
        com.breakfront.client.bf.BfDraw.fill(ctx, x, y, cw, ch, argb(BfTheme.PANEL, a));
        com.breakfront.client.bf.BfDraw.border(ctx, x, y, cw, ch, argb(BfTheme.GREEN_DIM, a));

        // 顶部色条 + 胜方
        int winner = ClientMatchState.lastResultOrdinal();
        boolean attWin = winner == 1;
        boolean defWin = winner == 2;
        int winCol = attWin ? BfTheme.GREEN : (defWin ? BfTheme.BLUE : BfTheme.MUTED);
        com.breakfront.client.bf.BfDraw.fill(ctx, x, y, cw, 3, argb(winCol, a));

        String head = attWin ? "进攻方获胜  ATTACKERS WIN"
                : (defWin ? "防守方获胜  DEFENDERS WIN" : "ROUND OVER  回合结束");
        int hw = font.getWidth(head);
        ctx.drawText(font, Text.literal(head), x + cw / 2 - hw / 2, y + 18,
                argb(winCol, a), false);

        String score = String.format("攻方击杀 %d    :    %d 守方击杀",
                ClientMatchState.attackerTeamKills(), ClientMatchState.defenderTeamKills());
        int sw2 = font.getWidth(score);
        ctx.drawText(font, Text.literal(score), x + cw / 2 - sw2 / 2, y + 48, argb(0xFFFFFFFF, a), false);

        String mvp = mvpLine();
        int mw = font.getWidth(mvp);
        ctx.drawText(font, Text.literal(mvp), x + cw / 2 - mw / 2, y + 86, argb(BfTheme.TEXT_DIM, a), false);
        String hint = "下一回合即将开始…";
        int hw2 = font.getWidth(hint);
        ctx.drawText(font, Text.literal(hint), x + cw / 2 - hw2 / 2, y + ch - 24,
                argb(BfTheme.FAINT, a), false);
    }

    private String mvpLine() {
        var top = ClientMatchState.board();
        if (top.isEmpty()) {
            return "本局暂无击杀";
        }
        var m = top.get(0);
        String side = m.sideOrdinal() == 0 ? "攻方" : "守方";
        return "MVP  " + m.name() + "（" + side + "） " + m.kills() + " 杀 / " + m.deaths() + " 死"
                + (m.headshots() > 0 ? " · " + m.headshots() + " 爆头" : "");
    }

    // ---- C2：战斗中顶部小比分带 ----

    private void renderScoreChip(DrawContext ctx, TextRenderer font, int sw) {
        String line = String.format("攻 %d : %d 守",
                ClientMatchState.attackerTeamKills(), ClientMatchState.defenderTeamKills());
        int w = font.getWidth(line);
        int x = sw - w - 14;
        int y = 36;
        ctx.fill(x - 6, y - 3, x + w + 6, y + font.fontHeight + 3, 0x66000000);
        ctx.drawText(font, Text.literal(line), x, y, BfTheme.TEXT_DIM, false);
    }

    // ================= HUD v3：顶部战况带 / 左下血量 / 右下武器 =================

    /** 顶栏 v4：细长半透明条（不再挡视野），中心 = 固定菱形目标带(ZoneMarkers)，左右=双方票数/得分。 */
    private void renderTopBar(DrawContext ctx, TextRenderer font, int sw) {
        int pillH = ZoneMarkers.PILL_H;
        int bw = Math.min(sw - 260, 600);
        if (bw < 280) {
            bw = Math.min(sw - 40, 600);
        }
        int x = (sw - bw) / 2;
        int y = ZoneMarkers.PILL_TOP;

        // 细长底条（高透明，不遮视野中心）
        ctx.fill(x, y, x + bw, y + pillH, 0x710B0F15);
        ctx.fill(x, y, x + bw, y + 1, 0x26FFFFFF);
        ctx.fill(x, y + pillH - 1, x + bw, y + pillH, 0x14FFFFFF);

        // 左侧：进攻方剩余部署票（顶栏唯一「进度数字」）
        int leftColW = 118;
        int lx = x + 14;
        int ly = y + 5;
        ctx.drawText(font, Text.literal("ATTACKERS  进攻方"), lx, ly, BfTheme.MUTED, false);
        String tk = String.valueOf(ClientMatchState.attackerTickets());
        ctx.drawText(font, Text.literal(tk), lx, ly + 11, 0xFFFFFFFF, true);
        ctx.drawText(font, Text.literal("部署剩余"), lx + font.getWidth(tk) + 6, ly + 15,
                BfTheme.FAINT, false);

        // 右侧：防守方得分（无票数制，仅显示击杀得分）
        int rightColW = 118;
        int rr = x + bw - 14;
        int ry = y + 5;
        String dl = "DEFENDERS  防守方";
        ctx.drawText(font, Text.literal(dl), rr - font.getWidth(dl), ry, BfTheme.MUTED, false);
        String dk = String.valueOf(ClientMatchState.defenderTeamKills());
        ctx.drawText(font, Text.literal(dk), rr - font.getWidth(dk), ry + 11, BfTheme.BLUE, true);
        String dSub = "得分";
        ctx.drawText(font, Text.literal(dSub), rr - font.getWidth(dSub), ry + 15, BfTheme.FAINT, false);

        // 中央：固定菱形目标带（含占点进度刻度环）
        int bandX = x + leftColW + 6;
        int bandW = bw - leftColW - rightColW - 12;
        List<ZoneView> zones = ClientMatchState.zones();
        if (!zones.isEmpty() && bandW > 60) {
            ZoneMarkers.renderRailInto(ctx, font, bandX, y + 1, bandW, pillH - 2, zones);
        }

        // 底部 3px：进攻方剩余票数进度条（唯一进度条，tickets/max；部署倒计时阶段即显示）
        if (ClientMatchState.attackerTicketsMax() > 0 && (ClientMatchState.phaseOrdinal() == 1
                || ClientMatchState.phaseOrdinal() == 2)) {
            int gx = x + 12;
            int gw = bw - 24;
            int gy = y + pillH - 4;
            ctx.fill(gx, gy, gx + gw, gy + 3, 0x2E0A0D12);
            float ratio = (float) ClientMatchState.attackerTickets()
                    / ClientMatchState.attackerTicketsMax();
            ratio = Math.max(0f, Math.min(1f, ratio));
            int fw = (int) (gw * ratio);
            if (fw > 0) {
                ctx.fill(gx, gy, gx + fw, gy + 3,
                        ratio > 0.3f ? BfTheme.GREEN : BfTheme.RED);
            }
        }

        // 顶部最左上：时钟 / 扇区 小字（无底板，不占视野）
        int phase = ClientMatchState.phaseOrdinal();
        String info = phase == 1
                ? "倒计时 " + (int) Math.max(0, Math.ceil(ClientMatchState.countdownRemainingSeconds()))
                : formatClock(ClientMatchState.matchRemainingSeconds())
                        + " · " + Math.min(ClientMatchState.sectorIndex() + 1, ClientMatchState.sectorCount())
                        + "/" + ClientMatchState.sectorCount() + " 扇区";
        ctx.drawText(font, Text.literal(info), 10, 10, BfTheme.FAINT, false);
    }

    /** 顶部战况带中的单个据点胶囊。 */
    private void drawTopChip(DrawContext ctx, TextRenderer font, int x, int y, int w, int h, ZoneView z) {
        long now = System.currentTimeMillis();
        Side owner = Side.values()[z.ownerOrdinal()];
        boolean attacker = owner == Side.ATTACKER;
        boolean contested = !attacker && z.meter() > 1e-3f;
        int edge = contested
                ? ((now % 600) < 300 ? 0xFFEFFFFF : BfTheme.CYAN)
                : (attacker ? BfTheme.GREEN : BfTheme.BLUE);
        ctx.fill(x, y, x + w, y + h, 0x4010161F);
        ctx.fill(x, y, x + w, y + 1, edge);
        ctx.fill(x, y + h - 1, x + w, y + h, edge);
        ctx.fill(x, y, x + 1, y + h, edge);
        ctx.fill(x + w - 1, y, x + w, y + h, edge);
        int lw = font.getWidth(z.letter());
        ctx.drawText(font, Text.literal(z.letter()), x + w / 2 - lw / 2, y + 4, 0xFFFFFFFF, false);
        // 底部进度线：攻占=满格黄；争夺=按推进；防守稳定=蓝
        int innerW = Math.max(2, w - 4);
        if (attacker || contested) {
            int prog = attacker ? innerW
                    : (int) (innerW * Math.max(0f, Math.min(1f, z.meter())));
            ctx.fill(x + 2, y + h - 3, x + 2 + Math.max(1, prog), y + h - 2, BfTheme.GREEN);
        } else {
            ctx.fill(x + 2, y + h - 3, x + w - 2, y + h - 2, BfTheme.BLUE);
        }
    }

    /**
     * 左下血量卡（v5：深底面板 + 1.8x 大数字 + 高亮血条，任何背景/光影下都一眼可读）
     * + 右下武器（带暗色矩形底衬）。
     */
    private void renderHealthWeapon(DrawContext ctx, TextRenderer font, int sw, int sh) {
        var player = MinecraftClient.getInstance().player;
        if (player == null) {
            return;
        }
        float hp = player.getHealth();
        float max = Math.max(1f, player.getMaxHealth());
        float ratio = Math.max(0f, Math.min(1f, hp / max));
        long now = System.currentTimeMillis();

        // —— 左下：血量卡 ——
        int cardW = 176;
        int cardH = 36;
        int cx = 10;
        int cy = sh - cardH - 8;
        ctx.fill(cx, cy, cx + cardW, cy + cardH, 0x9E0B0F15);
        ctx.fill(cx, cy, cx + cardW, cy + 1, 0x33FFFFFF); // 顶部高光
        ctx.fill(cx, cy + cardH - 1, cx + cardW, cy + cardH, 0x1C000000);
        int stateCol = ratio > 0.5f ? 0xFF6FE873
                : (ratio > 0.25f ? 0xFFF2C94C : 0xFFF0483E);
        if (ratio <= 0.25f) {
            // 低血量：红色呼吸（眨眼提醒）
            stateCol = (now % 900) < 450 ? 0xFFF0483E : 0xFFFFB3AA;
        }
        ctx.fill(cx, cy, cx + 2, cy + cardH, stateCol); // 左侧状态色条

        // 1.8x 大数字（血量本体）
        String hpText = String.valueOf((int) Math.ceil(hp));
        var ms = ctx.getMatrices();
        ms.push();
        ms.translate(cx + 18f, 0f, 0f);
        float big = 1.8f;
        ms.scale(big, big, 1f);
        ctx.drawText(font, Text.literal(hpText), 0, Math.round((cy + 2) / big),
                0xFFFFFFFF, true);
        ms.pop();
        // 最大血值小字（大数字右侧、底线对齐）
        String maxText = "/" + (int) max;
        int numW = (int) Math.ceil(font.getWidth(hpText) * big);
        ctx.drawText(font, Text.literal(maxText), cx + 18 + numW + 7,
                cy + 15, ratio <= 0.25f ? stateCol : BfTheme.TEXT_DIM, true);

        // 高亮血条（数字下方，状态色填充 + 底深槽 + 白高光顶线）
        int barX = cx + 8;
        int barW = cardW - 16;
        int barY = cy + cardH - 7;
        int barH = 4;
        ctx.fill(barX, barY, barX + barW, barY + barH, 0xE0000000);
        int fillW = (int) (barW * ratio);
        if (fillW > 0) {
            ctx.fill(barX, barY, barX + fillW, barY + barH, stateCol);
            ctx.fill(barX, barY, barX + fillW, barY + 1, 0xAAFFFFFF); // 亮部高光
        }

        // —— 右下：武器信息 + 矩形底衬 ——
        var stack = player.getMainHandStack();
        String wname = stack.isEmpty() ? "徒手  UNARMED" : stack.getName().getString();
        int pw = 216;
        int ph = 46;
        int px = sw - pw - 12;
        int py = sh - ph - 8;
        ctx.fill(px, py, px + pw, py + ph, 0xC010141B);
        ctx.fill(px, py, px + pw, py + 2, BfTheme.GREEN);
        ctx.fill(px, py + ph - 1, px + pw, py + ph, 0x24FFFFFF);
        ctx.drawText(font, Text.literal("当前武器  CURRENT"), px + 12, py + 7, BfTheme.MUTED, false);
        ctx.drawText(font, Text.literal(wname), px + 12, py + 19, 0xFFFFFFFF, true);
        String state = "READY  待命";
        ctx.drawText(font, Text.literal(state), px + 12, py + ph - 14, BfTheme.TEXT_DIM, false);
    }

    // ---- 左上：目标胶囊 ----

    private void renderObjective(DrawContext ctx, TextRenderer font, int sw) {
        List<ZoneView> zones = ClientMatchState.zones();
        int x = 10;
        int y = 8;
        ctx.drawText(font, Text.literal("OBJECTIVE  目标"), x, y, BfTheme.MUTED, false);
        int cy = y + font.fontHeight + 3;
        if (zones.isEmpty()) {
            return;
        }
        for (ZoneView z : zones) {
            int bw = 30;
            int bh = 16;
            ctx.fill(x, cy, x + bw, cy + bh, 0x99000000);
            Side owner = Side.values()[z.ownerOrdinal()];
            long t = System.currentTimeMillis();
            boolean contested = owner == Side.DEFENDER && z.meter() > 1e-3f;
            int edge = contested
                    ? ((t % 700) < 350 ? 0xFFEFFFFF : BfTheme.CYAN)
                    : (owner == Side.ATTACKER ? BfTheme.GREEN : BfTheme.BLUE);
            ctx.fill(x, cy, x + bw, cy + 1, edge);
            ctx.fill(x, cy, x + 1, cy + bh, edge);
            ctx.fill(x + bw - 1, cy, x + bw, cy + bh, edge);
            ctx.fill(x, cy + bh - 1, x + bw, cy + bh, edge);
            // 字母菱形占位：小方块字母
            ctx.drawText(font, Text.literal(z.letter()), x + 4, cy + 3, 0xFFFFFFFF, false);
            ctx.drawText(font, Text.literal(owner == Side.ATTACKER ? "占" : "守"),
                    x + bw - font.getWidth("占") - 3, cy + 3, edge, false);
            x += bw + 6;
            if (x > sw - 130) {
                break;
            }
        }
    }

    // ---- 中上：扇区/阶段 ----

    private void renderSectorPill(DrawContext ctx, TextRenderer font, int sw) {
        int phase = ClientMatchState.phaseOrdinal();
        String phaseText = PHASE_LABELS[Math.max(0, Math.min(phase, PHASE_LABELS.length - 1))];
        String txt = String.format("%s  ·  扇区 %d/%d",
                phaseText, Math.min(ClientMatchState.sectorIndex() + 1, ClientMatchState.sectorCount()),
                ClientMatchState.sectorCount());
        int tw = font.getWidth(txt);
        int x = (sw - tw) / 2;
        int y = 10;
        ctx.fill(x - 8, y - 3, x + tw + 8, y + font.fontHeight + 3, 0x66000000);
        ctx.drawText(font, Text.literal(txt), x, y, phase == 2 ? BfTheme.TEXT_DIM : BfTheme.GREEN, false);
    }

    // ---- 右上：时钟 + 部署资源 ----

    private void renderClockTickets(DrawContext ctx, TextRenderer font, int sw) {
        int phase = ClientMatchState.phaseOrdinal();
        String clock;
        if (phase == 1) {
            clock = String.format("%.0f", Math.max(0, ClientMatchState.countdownRemainingSeconds()));
        } else {
            clock = formatClock(ClientMatchState.matchRemainingSeconds());
        }
        int clockW = font.getWidth(clock);
        int cx = sw - 12 - clockW;
        // 分割竖线
        ctx.fill(cx - 10, 12, cx - 9, 12 + 20, BfTheme.PANEL_LINE);
        ctx.drawText(font, Text.literal(clock), cx, 10, 0xFFFFFFFF, false);
        ctx.drawText(font, Text.literal("TIME"), cx, 10 + font.fontHeight + 1, BfTheme.FAINT, false);
        // 部署资源
        String tick = String.valueOf(ClientMatchState.attackerTickets());
        int tkW = font.getWidth(tick);
        int tx = sw - 12 - tkW;
        int ticketsColor = ClientMatchState.attackerTickets() <= 10 ? BfTheme.RED : BfTheme.GREEN;
        ctx.drawText(font, Text.literal(tick), tx, 10, ticketsColor, false);
        ctx.drawText(font, Text.literal("DEPLOY  攻方"), tx, 10 + font.fontHeight + 1, BfTheme.FAINT, false);
        // 竖直锚线
        ctx.fill(sw - 14, 10, sw - 13, 10 + 24, ticketsColor == BfTheme.RED ? BfTheme.RED : BfTheme.GREEN);
    }

    // ---- 底部：据点推进 ----

    private void renderZoneProgress(DrawContext ctx, TextRenderer font, int sw, int sh) {
        List<ZoneView> zones = ClientMatchState.zones();
        if (zones.isEmpty()) {
            return;
        }
        int width = 200;
        int height = 4;
        int gap = 18;
        int total = zones.size() * gap;
        int startY = sh - 26 - total + gap;
        int x = (sw - width) / 2;

        for (int i = 0; i < zones.size(); i++) {
            ZoneView zone = zones.get(i);
            int y = startY + i * gap;
            ctx.fill(x - 2, y - 2, x + width + 2, y + height + 2, 0x77000000);
            Side owner = Side.values()[zone.ownerOrdinal()];
            float meter = Math.max(0f, Math.min(1f, zone.meter()));
            int fillColor = owner == Side.ATTACKER ? BfTheme.GREEN : BfTheme.BLUE;
            int progress = owner == Side.ATTACKER ? width : (int) (width * meter);
            if (progress > 0) {
                ctx.fill(x, y, x + progress, y + height, fillColor);
            }
            String label = String.format("%s", zone.letter());
            ctx.drawText(font, Text.literal(label), (sw - width) / 2 - 12, y - 3, 0xFFFFFFFF, false);
            String state = owner == Side.ATTACKER
                    ? "已占领"
                    : (meter > 1e-3f ? String.format("推进 %d%%", (int) (meter * 100)) : "防守中");
            int sw2 = font.getWidth(state);
            ctx.drawText(font, Text.literal(state), (sw + width) / 2 + 4, y - 3,
                    owner == Side.ATTACKER ? BfTheme.GREEN_DIM : BfTheme.TEXT_DIM, false);
        }
    }

    // ---- 击杀流（右上时钟下方，动画） ----

    private void renderKillFeed(DrawContext ctx, TextRenderer font, int sw) {
        long now = System.currentTimeMillis();
        List<KillEvent> feed = ClientMatchState.killFeed();
        int x = sw - 250;
        int y = 112; // 位于顶部战况带与右上雷达下方
        int shown = 0;
        for (KillEvent row : feed) {
            long ageMs = now - row.addedAt();
            if (ageMs > 6500 || ageMs < 0) {
                continue;
            }
            if (shown >= 5) {
                break;
            }
            // 淡入 240ms / 淡出最后 700ms
            float inA = Math.min(1f, ageMs / 240f);
            float outA = Math.min(1f, Math.max(0f, (6500 - ageMs) / 700f));
            int alpha = (int) (200 * inA * outA);
            int dy = (int) ((1 - inA) * 8);

            String head = row.headshot() ? "爆头 " : "";
            String line = row.killer() + "  击杀  " + row.victim();
            ctx.drawText(font, Text.literal(head), x, y + dy, argb(BfTheme.TEAL, alpha), false);
            int hw = font.getWidth(head);
            ctx.drawText(font, Text.literal(line), x + hw, y + dy, argb(0xFFF2F4F8, alpha), false);
            if (row.attackerDied()) {
                ctx.drawText(font, Text.literal("●攻方减员"), x + hw + font.getWidth(line) + 8, y + dy,
                        argb(BfTheme.RED, alpha), false);
            }
            y += font.fontHeight + 5;
            shown++;
        }
    }

    private static int argb(int rgb, int a) {
        int aa = Math.max(0, Math.min(255, a));
        return (aa << 24) | (rgb & 0xFFFFFF);
    }

    private static String formatClock(float seconds) {
        int total = (int) Math.max(0, Math.ceil(seconds));
        return String.format("%02d:%02d", total / 60, total % 60);
    }
}
