package com.breakfront.client.hud;

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

    public void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.options == null) {
            return;
        }
        TextRenderer font = client.textRenderer;
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();
        int phase = ClientMatchState.phaseOrdinal();

        if (phase == 3) {
            renderRoundOver(context, font, sw, sh);
            return;
        }
        if (phase != 1 && phase != 2) {
            return; // 大厅不渲染（大厅面板另行处理）
        }
        renderObjective(context, font, sw);
        renderSectorPill(context, font, sw);
        renderClockTickets(context, font, sw);
        renderScoreChip(context, font, sw);
        renderZoneProgress(context, font, sw, sh);
        renderKillFeed(context, font, sw);
        ZoneMarkers.render(context, font, sw, sh);
    }

    // ---- C1：结算层（ROUND_END）----

    private void renderRoundOver(DrawContext ctx, TextRenderer font, int sw, int sh) {
        com.breakfront.client.bf.BfDraw.fill(ctx, 0, 0, sw, sh, 0xCC05070A);
        int cw = Math.min(sw - 60, 400);
        int ch = 148;
        int x = sw / 2 - cw / 2;
        int y = sh / 2 - ch / 2;
        com.breakfront.client.bf.BfDraw.fill(ctx, x, y, cw, ch, BfTheme.PANEL);
        com.breakfront.client.bf.BfDraw.border(ctx, x, y, cw, ch, BfTheme.YELLOW_DIM);
        com.breakfront.client.bf.BfDraw.fill(ctx, x, y, cw, 3, BfTheme.YELLOW);

        String head = "ROUND OVER  回合结束";
        int hw = font.getWidth(head);
        ctx.drawText(font, Text.literal(head), x + cw / 2 - hw / 2, y + 16, BfTheme.YELLOW, false);

        String score = String.format("攻方击杀 %d    :    %d 守方击杀",
                ClientMatchState.attackerTeamKills(), ClientMatchState.defenderTeamKills());
        int sw2 = font.getWidth(score);
        ctx.drawText(font, Text.literal(score), x + cw / 2 - sw2 / 2, y + 42, 0xFFFFFFFF, false);

        String mvp = mvpLine();
        int mw = font.getWidth(mvp);
        ctx.drawText(font, Text.literal(mvp), x + cw / 2 - mw / 2, y + 76, BfTheme.TEXT_DIM, false);
        String hint = "下一回合即将开始…";
        int hw2 = font.getWidth(hint);
        ctx.drawText(font, Text.literal(hint), x + cw / 2 - hw2 / 2, y + ch - 22, BfTheme.FAINT, false);
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
                    ? ((t % 700) < 350 ? 0xFFFFFFFF : 0xFFF5D44A)
                    : (owner == Side.ATTACKER ? 0xFFF5D44A : BfTheme.BLUE);
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
        ctx.drawText(font, Text.literal(txt), x, y, phase == 2 ? BfTheme.TEXT_DIM : BfTheme.YELLOW, false);
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
        int ticketsColor = ClientMatchState.attackerTickets() <= 10 ? BfTheme.RED : BfTheme.YELLOW;
        ctx.drawText(font, Text.literal(tick), tx, 10, ticketsColor, false);
        ctx.drawText(font, Text.literal("DEPLOY  攻方"), tx, 10 + font.fontHeight + 1, BfTheme.FAINT, false);
        // 竖直锚线
        ctx.fill(sw - 14, 10, sw - 13, 10 + 24, ticketsColor == BfTheme.RED ? BfTheme.RED : BfTheme.YELLOW);
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
            int fillColor = owner == Side.ATTACKER ? BfTheme.YELLOW : BfTheme.BLUE;
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
                    owner == Side.ATTACKER ? BfTheme.YELLOW_DIM : BfTheme.TEXT_DIM, false);
        }
    }

    // ---- 击杀流（右上时钟下方，动画） ----

    private void renderKillFeed(DrawContext ctx, TextRenderer font, int sw) {
        long now = System.currentTimeMillis();
        List<KillEvent> feed = ClientMatchState.killFeed();
        int x = sw - 220;
        int y = 56;
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
            ctx.drawText(font, Text.literal(head), x, y + dy, argb(0xFFF5D44A, alpha), false);
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
