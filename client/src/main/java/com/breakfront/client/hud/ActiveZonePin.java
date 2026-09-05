package com.breakfront.client.hud;

import com.breakfront.client.bf.BfEasing;
import com.breakfront.client.bf.BfTheme;
import com.breakfront.client.state.ClientMatchState;
import com.breakfront.client.state.ClientMatchState.ZoneView;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;

/**
 * 领地进入钉卡（#40 收尾）：玩家进入某领地后，该据点的地标菱形从顶栏对应槽位
 * 「平滑转移到屏幕上方固定位置」——滑入的卡片持续钉住（显示领地字母/归属/推进），
 * 离开领地则反向滑回顶栏槽位消失。
 *
 * 纯几何矢量绘制；状态跨帧保持，进场 350ms easeOutCubic 滑移 + 120ms 淡入。
 */
public final class ActiveZonePin {

    private static final int PIN_W = 236;
    private static final int PIN_H = 46;
    private static final long SLIDE_MS = 350;
    private static final long FADE_MS = 160;

    /** 当前钉住领地 id（null=不在任何领地内）。 */
    private static String pinnedZoneId;
    /** 动效方向：true=进入(0→1)，false=离开(1→0)。 */
    private static boolean entering = true;
    /** 当前动效进度 0..1。 */
    private static double progress = 0.0;
    /** 离开后需再经一个 fade 周期才允许重新进入（防边缘抖动）。 */
    private static long cooldownUntil;

    private ActiveZonePin() {
    }

    /** 领地方形包含判定（与服务端 ZoneAnchor.contains 语义一致）。 */
    private static boolean contains(ZoneView zv, double px, double pz) {
        return Math.abs(px - zv.worldX()) <= zv.radius() && Math.abs(pz - zv.worldZ()) <= zv.radius();
    }

    public static void render(DrawContext ctx, TextRenderer font, int sw) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return;
        }
        List<ZoneView> zones = ClientMatchState.zones();
        if (zones.isEmpty()) {
            pinnedZoneId = null;
            return;
        }
        double px = client.player.getX();
        double pz = client.player.getZ();
        long now = System.currentTimeMillis();

        // 1) 解析当前所在领地（优先第一个包含玩家的）
        ZoneView active = null;
        for (ZoneView z : zones) {
            if (contains(z, px, pz)) {
                active = z;
                break;
            }
        }
        String activeId = active == null ? null : active.zoneId();

        // 2) 状态推进：进入/离开切换
        if (activeId != null && !activeId.equals(pinnedZoneId) && now >= cooldownUntil) {
            pinnedZoneId = activeId;
            entering = true;
            progress = 0.0;
        } else if (activeId == null && pinnedZoneId != null) {
            entering = false;
        }
        if (pinnedZoneId == null) {
            return;
        }

        // 3) 进度推进（用帧间隔近似；easeOut 后 0→1）
        if (entering) {
            progress = Math.min(1.0, progress + 16.0 / SLIDE_MS);
            if (progress >= 1.0) {
                progress = 1.0;
            }
        } else {
            progress = Math.max(0.0, progress - 16.0 / SLIDE_MS);
            if (progress <= 0.0) {
                pinnedZoneId = null;
                cooldownUntil = now + 500;
                return;
            }
        }

        // 4) 找回当前 zone view（id 可能随对局重建）
        ZoneView z = zones.stream().filter(v -> v.zoneId().equals(pinnedZoneId))
                .findFirst().orElse(null);
        if (z == null) {
            pinnedZoneId = null;
            return;
        }
        if (active != null && !active.zoneId().equals(z.zoneId())) {
            // 玩家中途换领地但旧卡还在动画中：直接切目标重新入场
            pinnedZoneId = active.zoneId();
            entering = true;
            progress = 0.0;
            z = active;
        }

        // 5) 几何：源=该据点在顶栏槽位的菱形中心，目标=屏幕上方固定卡位
        int chipX = railSlotCenterX(sw, zones.indexOf(z));
        int chipY = ZoneMarkers.PILL_TOP + ZoneMarkers.PILL_H / 2;
        int pinX = (sw - PIN_W) / 2;
        int pinY = ZoneMarkers.PILL_TOP + ZoneMarkers.PILL_H + 8;

        double t = BfEasing.easeOutCubic(progress);
        int curX = (int) (chipX + (pinX - chipX) * t);
        int curY = (int) (chipY + (pinY - chipY) * t);
        int alpha = (int) (0xFF * (entering ? progress : (1.0 - progress) * 0.8));

        // 6) 移动中的大号菱形字母（滑动主体）
        int diaSize = (int) (16 + (30 - 16) * Math.min(1.0, progress * 3.0));
        int color = zoneColor(z);
        drawDiamond(ctx, curX, curY, diaSize, color, alpha);
        drawLetter(ctx, font, z.letter(), curX, curY - 1, diaSize, alpha);

        // 7) 进度 >0.35 后淡入信息卡（固定槽位）
        if (progress > 0.35) {
            double cardT = BfEasing.easeOutCubic(Math.min(1.0, (progress - 0.35) / 0.55));
            int cardAlpha = (int) (0xE0 * cardT * (entering ? 1.0 : 0.6));
            if (cardAlpha > 8) {
                drawCard(ctx, font, z, pinX, pinY, cardAlpha);
            }
        }
    }

    /** 顶栏槽位菱形中心 x（与 renderTopBar 布局同参数推导）。 */
    private static int railSlotCenterX(int sw, int zoneIndex) {
        int bw = Math.min(sw - 260, 600);
        if (bw < 280) {
            bw = Math.min(sw - 40, 600);
        }
        int x = (sw - bw) / 2 + 124;
        int bandW = bw - 248;
        List<ZoneView> zones = ClientMatchState.zones();
        int n = Math.max(1, zones.size());
        double slot = bandW / (double) n;
        return (int) (x + slot * (zoneIndex + 0.5));
    }

    private static int zoneColor(ZoneView z) {
        boolean attacker = z.ownerOrdinal() == 0;
        boolean contested = !attacker && z.meter() > 1e-3f;
        return contested ? BfTheme.YELLOW : (attacker ? BfTheme.YELLOW : BfTheme.BLUE);
    }

    private static void drawDiamond(DrawContext ctx, int cx, int cy, int size, int rgb, int a) {
        if (a <= 4) {
            return;
        }
        int r = ((rgb >> 16) & 0xFF) * a / 255;
        int g = ((rgb >> 8) & 0xFF) * a / 255;
        int b = (rgb & 0xFF) * a / 255;
        int col = (a << 24) | (r << 16) | (g << 8) | b;
        int h = size / 2;
        // 外描边（放大一层）先画，再画本体
        ctx.fill(cx - h - 2, cy - 2, cx + h + 2, cy + 2, 0x33000000);
        ctx.fill(cx - 2, cy - h - 2, cx + 2, cy + h + 2, 0x33000000);
        // 近似菱形用两层矩形堆叠（简洁可靠的 2D 表现）
        ctx.fill(cx - h / 2, cy - h, cx + h / 2, cy + h, col);
        ctx.fill(cx - h, cy - h / 2, cx + h, cy + h / 2, col);
    }

    private static void drawLetter(DrawContext ctx, TextRenderer font, String letter,
                                   int cx, int cy, int size, int a) {
        int col = ((a * 3 / 4) << 24) | 0x00FFFFFF;
        int lw = font.getWidth(letter);
        ctx.drawText(font, Text.literal(letter), cx - lw / 2, cy - 4,
                col | 0xFF000000, false);
    }

    private static void drawCard(DrawContext ctx, TextRenderer font, ZoneView z,
                                 int x, int y, int a) {
        int r = (BfTheme.PANEL >> 16) & 0xFF;
        int g = (BfTheme.PANEL >> 8) & 0xFF;
        int b = BfTheme.PANEL & 0xFF;
        int bg = (a << 24) | (r << 16) | (g << 8) | b;
        ctx.fill(x, y, x + PIN_W, y + PIN_H, bg);
        ctx.fill(x, y, x + PIN_W, y + 1, BfTheme.YELLOW); // 顶强调线
        int edge = zoneColor(z);
        ctx.fill(x, y + 1, x + 3, y + PIN_H - 1, edge);   // 左侧领地色边

        int ta = a * 3 / 4;
        // 标题：领地字母 + 语义
        String title = "OBJECTIVE  " + z.letter() + "  ·  " + zoneStateText(z);
        ctx.drawText(font, Text.literal(title), x + 12, y + 6,
                (ta << 24) | (BfTheme.TEXT & 0xFFFFFF), false);
        // 说明行
        String sub = z.ownerOrdinal() == 0 ? "据点已由进攻方控制"
                : (z.meter() > 1e-3f ? "据点争夺中 · 进攻方推进" : "据点防守稳固");
        ctx.drawText(font, Text.literal(sub), x + 12, y + 20,
                (ta << 24) | (BfTheme.MUTED & 0xFFFFFF), false);
        // 进度条
        int gx = x + 12;
        int gw = PIN_W - 24;
        int gy = y + PIN_H - 8;
        ctx.fill(gx, gy, gx + gw, gy + 3, 0x40000000);
        float ratio = z.ownerOrdinal() == 0 ? 1f : Math.max(0f, Math.min(1f, z.meter()));
        if (ratio > 0.01f) {
            ctx.fill(gx, gy, gx + (int) (gw * ratio), gy + 3, zoneColor(z));
        }
    }

    private static String zoneStateText(ZoneView z) {
        if (z.ownerOrdinal() == 0) {
            return "已占领";
        }
        return z.meter() > 1e-3f ? "争夺中" : "防守方";
    }

    /** 调试/布局预留：清空钉卡状态（供切屏重置）。 */
    public static void reset() {
        pinnedZoneId = null;
        progress = 0.0;
        cooldownUntil = 0;
    }
}
