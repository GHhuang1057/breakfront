package com.breakfront.client.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 最小矢量 HUD（P1 验证管线）。
 *
 * 目的：验证「程序化几何绘制 + 平滑文本」在 VulkanMod 同屏下的渲染路径可行
 * （charter §4.7 第 5 条的一票否决冒烟项），并作为后续完整 HUD 的骨架。
 *
 * 布局（BF 风格草稿，均为占位演示数据，后续对接 breakfront 核心事件）：
 * - 顶部：攻方部署资源 | 剩余时间
 * - 右下：击杀事件流（自动淡出）
 * - 底部中央：当前目标点占领进度条
 *
 * 纪律：仅用 DrawContext.fill / drawText 等标准 API，不触碰 OpenGL。
 */
public class BreakfrontHud {

    private static final int ACCENT = 0xFFE8622C;   // 战术橙
    private static final int PANEL = 0x66000000;     // 半透明黑
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0x99FFFFFF;

    /** 占位演示数据（P1 验证用；后续改为从核心事件订阅）。 */
    private final Deque<KillRow> killFeed = new ArrayDeque<>();
    private int attackerTickets = 250;
    private double matchRemaining = 1500.0;
    private double zoneProgress = 0.62; // 目标点 A1 攻方推进度
    private long lastDemoPush = 0;
    private int demoSeq = 0;

    public void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.options == null) {
            return; // 仅游戏中渲染
        }
        TextRenderer font = client.textRenderer;
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();

        renderTopStatus(context, font, sw);
        renderZoneBar(context, font, sw, sh);
        renderKillFeed(context, font, sw);
    }

    private void renderTopStatus(DrawContext ctx, TextRenderer font, int sw) {
        String status = String.format("攻方部署 %d    剩余 %s",
                attackerTickets, formatClock(matchRemaining));
        int w = font.getWidth(status);
        int x = (sw - w) / 2;
        ctx.drawText(font, Text.literal(status), x, 10, TEXT, false);

        String phase = "进攻方推进  ·  扇区 1 / 4";
        int pw = font.getWidth(phase);
        ctx.drawText(font, Text.literal(phase), (sw - pw) / 2, 22, MUTED, false);
    }

    private void renderZoneBar(DrawContext ctx, TextRenderer font, int sw, int sh) {
        int width = 240;
        int height = 8;
        int x = (sw - width) / 2;
        int y = sh - 34;

        // 底槽（圆角近似：主矩形 + 两端小方块叠加；P1 先行矩形，圆角几何随后续设计令牌实现）
        ctx.fill(x - 2, y - 2, x + width + 2, y + height + 2, PANEL);
        ctx.fill(x, y, x + (int) (width * zoneProgress), y + height, ACCENT);

        String label = String.format("目标点 A1  ·  占领 %.0f%%", zoneProgress * 100);
        int lw = font.getWidth(label);
        ctx.drawText(font, Text.literal(label), (sw - lw) / 2, y - 12, TEXT, false);
    }

    private void renderKillFeed(DrawContext ctx, TextRenderer font, int sw) {
        long now = System.currentTimeMillis();
        if (now - lastDemoPush > 6000) {
            lastDemoPush = now;
            demoSeq++;
            pushKill(String.format("BF_%02d 击杀了 OP_%02d", demoSeq * 7 % 64 + 1, demoSeq * 13 % 64 + 1));
        }

        long nowMs = System.currentTimeMillis();
        int y = 12;
        killFeed.removeIf(row -> nowMs - row.addedAt > 6000);
        for (KillRow row : killFeed) {
            int w = font.getWidth(row.text);
            ctx.fill(sw - w - 16, y - 1, sw - 6, y + font.fontHeight + 1, PANEL);
            ctx.drawText(font, Text.literal(row.text), sw - w - 12, y, TEXT, false);
            y += font.fontHeight + 6;
        }
    }

    private void pushKill(String text) {
        killFeed.addFirst(new KillRow(text, System.currentTimeMillis()));
        while (killFeed.size() > 5) {
            killFeed.removeLast();
        }
    }

    private static String formatClock(double seconds) {
        int total = (int) Math.max(0, Math.ceil(seconds));
        return String.format("%02d:%02d", total / 60, total % 60);
    }

    private record KillRow(String text, long addedAt) {
    }
}
