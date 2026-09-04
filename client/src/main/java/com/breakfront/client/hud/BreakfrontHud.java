package com.breakfront.client.hud;

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
 * Breakfront 战场 HUD（P1 · 真数据版）。
 *
 * 数据来源：ClientMatchState（由 core → client 的 S2C 状态帧/击杀流驱动）。
 * 渲染纪律（charter §4.7）：只使用几何绘制与文本，不用像素贴图；不触碰 OpenGL。
 */
public class BreakfrontHud {

    private static final String[] PHASE_LABELS = {
            "大厅", "部署倒计时", "战斗中", "结算", "战场重置"
    };

    private static final int ACCENT = 0xFFE8622C;
    private static final int DEF_BLUE = 0xFF4DA6FF;
    private static final int PANEL = 0x99000000;
    private static final int PANEL_SOFT = 0x66000000;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0x99FFFFFF;

    public void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.options == null) {
            return; // 仅游戏中渲染
        }
        TextRenderer font = client.textRenderer;
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();

        renderTopStatus(context, font, sw);
        renderZoneBars(context, font, sw, sh);
        renderKillFeed(context, font, sw);
    }

    private void renderTopStatus(DrawContext ctx, TextRenderer font, int sw) {
        int phase = ClientMatchState.phaseOrdinal();
        String phaseText = (phase >= 0 && phase < PHASE_LABELS.length)
                ? PHASE_LABELS[phase] : "未知";

        String clock;
        if (phase == 1) {
            clock = "开局 " + String.format("%.0f", ClientMatchState.countdownRemainingSeconds());
        } else {
            clock = "剩余 " + formatClock(ClientMatchState.matchRemainingSeconds());
        }
        String status = String.format("攻方部署 %d    %s",
                ClientMatchState.attackerTickets(), clock);
        int w = font.getWidth(status);
        ctx.drawText(font, Text.literal(status), (sw - w) / 2, 10, TEXT, false);

        String sub = String.format("%s  ·  扇区 %d / %d", phaseText,
                Math.min(ClientMatchState.sectorIndex() + 1, ClientMatchState.sectorCount()),
                ClientMatchState.sectorCount());
        int pw = font.getWidth(sub);
        ctx.drawText(font, Text.literal(sub), (sw - pw) / 2, 22, MUTED, false);
    }

    /** 当前扇区各据点进度条（BF 风格细条，多据点则纵排）。 */
    private void renderZoneBars(DrawContext ctx, TextRenderer font, int sw, int sh) {
        List<ZoneView> zones = ClientMatchState.zones();
        if (zones.isEmpty()) {
            return;
        }
        int width = 220;
        int height = 6;
        int gap = 24;
        int total = zones.size() * gap;
        int startY = sh - 40 - total + gap / 2;
        int x = (sw - width) / 2;

        for (int i = 0; i < zones.size(); i++) {
            ZoneView zone = zones.get(i);
            int y = startY + i * gap;

            // 底槽
            ctx.fill(x - 2, y - 2, x + width + 2, y + height + 2, PANEL);
            Side owner = Side.values()[zone.ownerOrdinal()];
            float meter = Math.max(0f, Math.min(1f, zone.meter()));
            int fillColor = owner == Side.ATTACKER ? ACCENT : DEF_BLUE;
            // 攻方已占显示满条；守方持有时 meter 为攻方推进进度（红色增长条）
            int progress = owner == Side.ATTACKER
                    ? width
                    : (int) (width * meter);
            ctx.fill(x, y, x + progress, y + height, fillColor);

            String stateText = owner == Side.ATTACKER
                    ? zone.zoneId() + " 已占领"
                    : String.format("%s 推进 %.0f%%", zone.zoneId(), meter * 100);
            int tw = font.getWidth(stateText);
            ctx.drawText(font, Text.literal(stateText), (sw - tw) / 2, y - 11, TEXT, false);
        }
    }

    private void renderKillFeed(DrawContext ctx, TextRenderer font, int sw) {
        long now = System.currentTimeMillis();
        List<KillEvent> feed = ClientMatchState.killFeed();
        int y = 12;
        int shown = 0;
        for (KillEvent row : feed) {
            if (now - row.addedAt() > 6000) {
                continue;
            }
            if (shown >= 5) {
                break;
            }
            String line = row.killer() + (row.headshot() ? " [爆头]" : "")
                    + " 击杀了 " + row.victim() + (row.attackerDied() ? "（攻方）" : "");
            int w = font.getWidth(line);
            ctx.fill(sw - w - 16, y - 1, sw - 6, y + font.fontHeight + 1, PANEL_SOFT);
            ctx.drawText(font, Text.literal(line), sw - w - 12, y, TEXT, false);
            y += font.fontHeight + 6;
            shown++;
        }
    }

    private static String formatClock(float seconds) {
        int total = (int) Math.max(0, Math.ceil(seconds));
        return String.format("%02d:%02d", total / 60, total % 60);
    }
}
