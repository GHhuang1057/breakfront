package com.breakfront.client.hud;

import com.breakfront.client.bf.BfDraw;
import com.breakfront.client.bf.BfTheme;
import com.breakfront.client.state.ClientMatchState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * BREAKFRONT 精简 F3 调试层（#44-2 BF 迷你 HUD）。
 *
 * <p>接管原版 DebugHud 的整块文本（见 {@code DebugHudOverlayMixin}），改以 BF 科幻蓝绿
 * 风格在左上角绘制：坐标 / 朝向 / 帧率 / 所在据点 / 阶段与时钟。全矢量自绘，无贴图。
 *
 * <p>绘制入口只依赖 DrawContext + MinecraftClient，不读 DebugHud 内部字段，避免与原版
 * 数据结构耦合；数据全部来自 ClientMatchState 与玩家实体。
 */
public final class BfDebugOverlay {

    private BfDebugOverlay() {
    }

    public static void draw(DrawContext ctx, MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            return;
        }

        Vec3d p = client.player.getPos();
        int x = (int) Math.floor(p.x);
        int y = (int) Math.floor(p.y);
        int z = (int) Math.floor(p.z);

        String facing = client.player.getHorizontalFacing().getName().toUpperCase();
        int yaw = (int) client.player.getYaw();
        int pitch = (int) client.player.getPitch();

        int fps = client.getCurrentFps();

        String zone = zoneLetter(p.x, p.z);

        int ph = ClientMatchState.phaseOrdinal();
        String phaseLabel = phaseLabel(ph);
        float clock = (ph == 1)
                ? ClientMatchState.countdownRemainingSeconds()
                : ClientMatchState.matchRemainingSeconds();
        String clockStr = fmtTime(clock);

        List<String> lines = new ArrayList<>();
        lines.add("XYZ   " + x + " / " + y + " / " + z);
        lines.add("FACE  " + facing + "   yaw " + yaw + "  pitch " + pitch);
        lines.add("FPS   " + fps);
        lines.add("ZONE  " + zone);
        lines.add("PHASE " + phaseLabel + "   " + clockStr);

        // 布局
        int padX = 12, padY = 10, gap = 5;
        int lineH = client.textRenderer.fontHeight;
        int innerW = 0;
        for (String s : lines) {
            innerW = Math.max(innerW, client.textRenderer.getWidth(s));
        }
        int boxW = innerW + padX * 2;
        int boxH = padY * 2 + lineH + gap + lines.size() * (lineH + gap);

        int ox = 8, oy = 8;

        // 半透明面板底
        BfDraw.fill(ctx, ox, oy, boxW, boxH, BfTheme.PANEL);
        // 左侧强调条
        BfDraw.fill(ctx, ox, oy, 3, boxH, BfTheme.TEAL);
        // 描边
        BfDraw.border(ctx, ox, oy, boxW, boxH, BfTheme.PANEL_LINE);

        int tx = ox + padX;
        int ty = oy + padY;
        // 标题
        ctx.drawText(client.textRenderer, Text.literal("BREAKFRONT  DEBUG"),
                tx, ty, BfTheme.TEAL, false);
        ty += lineH + gap;
        for (int i = 0; i < lines.size(); i++) {
            // ZONE 行用冷青高亮，其余用主文本色
            int col = (i == 3) ? BfTheme.CYAN : BfTheme.TEXT;
            ctx.drawText(client.textRenderer, Text.literal(lines.get(i)),
                    tx, ty, col, false);
            ty += lineH + gap;
        }
    }

    /** 当前玩家水平位置落入的据点字母（按 ZoneView 半径做圆形判定）。 */
    private static String zoneLetter(double px, double pz) {
        for (ClientMatchState.ZoneView z : ClientMatchState.zones()) {
            double dx = px - z.worldX();
            double dz = pz - z.worldZ();
            if (dx * dx + dz * dz <= z.radius() * z.radius()) {
                return z.letter();
            }
        }
        return "—";
    }

    private static String phaseLabel(int ph) {
        return switch (ph) {
            case 0 -> "STANDBY";
            case 1 -> "COUNTDOWN";
            case 2 -> "BATTLE";
            case 3 -> "RESULT";
            default -> "PHASE " + ph;
        };
    }

    private static String fmtTime(float sec) {
        if (sec < 0) {
            return "--:--";
        }
        int s = (int) sec;
        int m = s / 60;
        int r = s % 60;
        return (m < 10 ? "0" : "") + m + ":" + (r < 10 ? "0" : "") + r;
    }
}
