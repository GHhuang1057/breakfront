package com.breakfront.client.hud;

import com.breakfront.client.bf.BfDraw;
import com.breakfront.client.bf.BfGlow;
import com.breakfront.client.bf.BfTheme;
import com.breakfront.client.state.ClientMatchState;
import com.breakfront.client.state.ClientMatchState.KillEvent;
import com.breakfront.client.state.ClientMatchState.ZoneView;
import com.breakfront.game.Side;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

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

    // 左下竖向堆叠布局常量
    private static final int LEFT_X = 12;     // 血量卡/小地图统一左缘
    private static final int BOTTOM = 10;     // 距屏幕底边
    private static final int GAP = 6;         // 血量卡与下方小地图间隙
    // TaCZ NBT 读取键（稳健：缺失即降级，绝不抛到渲染外）
    private static final String NBT_GUN_ID = "GunId";
    private static final String NBT_AMMO_NOW = "GunCurrentAmmoCount";
    private static final String TACZ_AMMO_ITEM = "tacz:ammo";

    // 每帧布局/视差状态（渲染线程内写入读取）
    private int miniSize;
    private double bobDx, bobDy;
    private float lastHp = 20f;
    private long dmgFlashUntil = 0;

    // 氛围层状态：受击暗角脉冲计时 + 上一帧血量（用于检测掉血）
    private long atmoDmgUntil = 0;
    private float lastAtmoHp = 20f;

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
        // —— 视野视差（view bob）：仅 hud.bob=true 时启用；柔和化（乘 0.22、单轴 ≤0.7px）——
        bobDx = 0;
        bobDy = 0;
        if (BfHudPrefs.isBobEnabled() && client.player != null) {
            var b = BfViewBob.compute(client.player);
            double k = 0.22 * BfHudPrefs.getShake();
            bobDx = clampAbs(b.dx() * k, 0.7);
            bobDy = clampAbs(b.dy() * k, 0.7);
        }

        miniSize = (int) Math.min(140, sh * 0.22);

        renderTopBar(context, font, sw);
        renderHealth(context, font, sw, sh);
        renderWeapon(context, font, sw, sh);
        renderKillFeed(context, font, sw);
        renderHitMarkers(context, font, sw, sh);
        // 小地图：左下角竖向堆叠于血量卡下方（坐标换算沿用现有，叠加视差偏移）
        BfMinimap.render(context, font, LEFT_X, sh - miniSize - BOTTOM, miniSize, bobDx, bobDy);
        ZoneMarkers.render(context, font, sw, sh);
        ActiveZonePin.render(context, font, sw); // #40：进入领地→顶栏钉卡（平滑转移）

        // 氛围层（暗角/低血量）与准星：仅"正在游玩、无全屏界面"时绘制
        boolean screenOpen = client.currentScreen != null;
        if (!screenOpen && BfHudPrefs.isVignetteEnabled()) {
            renderAtmosphere(context, font, sw, sh, client);
        }
        if (!screenOpen && BfHudPrefs.isCrosshairEnabled()) {
            renderCrosshair(context, font, sw, sh);
        }
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
            if (ageMs < 0 || ageMs > 520) {
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
            // 击杀/爆头：叠加 GD656 风格扩张环
            if (m.kind() >= 2) {
                int rgb = m.kind() == 3 ? BfTheme.GREEN : BfTheme.RED;
                renderKillRing(ctx, cx, cy, ageMs, rgb, m.kind() == 3);
            }
        }
    }

    // 击杀反馈环（对标 GD656 IconRingEffect）：延迟 100ms 后 300ms 内由 ~10px 扩张到 ~42px，
    // 透明度 (1-t)^2 衰减；爆头（kind 3）叠加第二圈（再延迟 100ms、更粗）。
    private void renderKillRing(DrawContext ctx, int cx, int cy, long ageMs, int rgb, boolean doubleRing) {
        final long DELAY = 100, DUR = 300;
        long e1 = ageMs - DELAY;
        if (e1 >= 0 && e1 <= DUR) {
            float t = (float) e1 / DUR;
            float ease = 1f - (float) Math.pow(1f - t, 3); // easeOutCubic
            float alpha = (1f - t) * (1f - t);
            float radius = 10f + 32f * ease;
            float thick = 3f * (1f - t);
            BfDraw.ring(ctx, cx, cy, radius, thick, argb(rgb, (int) (alpha * 215)));
        }
        if (doubleRing) {
            long e2 = ageMs - DELAY - DELAY;
            if (e2 >= 0 && e2 <= DUR) {
                float t = (float) e2 / DUR;
                float ease = 1f - (float) Math.pow(1f - t, 3);
                float alpha = (1f - t) * (1f - t);
                float radius = 10f + 32f * ease + 6f; // 外圈略大
                float thick = 3f * 1.8f * (1f - t);
                BfDraw.ring(ctx, cx, cy, radius, thick, argb(rgb, (int) (alpha * 165)));
            }
        }
    }

    // ---- W6：BF2042 风格准星（中心青点 + 四向细线，间隙随移动张开） ----

    private void renderCrosshair(DrawContext ctx, TextRenderer font, int sw, int sh) {
        var player = MinecraftClient.getInstance().player;
        if (player == null) {
            return;
        }
        int cx = sw / 2;
        int cy = sh / 2 - 1;
        // 水平移动速度 → 张开量（静立 ~3px，全速 ~10px）近似精度反馈
        var v = player.getVelocity();
        double hSpeed = Math.hypot(v.x, v.z);
        double move = Math.min(1.0, hSpeed / 0.16);
        int gap = (int) (3 + move * 7);
        int len = 5;
        int thick = 1;
        int col = (225 << 24) | 0xF2F4F8; // 近白
        int dot = BfTheme.TEAL;
        // 中心青点
        BfDraw.fill(ctx, cx - 1, cy - 1, 2, 2, dot);
        // 上
        BfDraw.fill(ctx, cx, cy - gap - len, thick, len, col);
        // 下
        BfDraw.fill(ctx, cx, cy + gap, thick, len, col);
        // 左
        BfDraw.fill(ctx, cx - gap - len, cy, len, thick, col);
        // 右
        BfDraw.fill(ctx, cx + gap, cy, len, thick, col);
    }

    // ---- W6：氛围层（受击暗角脉冲 + 低血量呼吸） ----

    private void renderAtmosphere(DrawContext ctx, TextRenderer font, int sw, int sh, MinecraftClient client) {
        var player = client.player;
        if (player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        float hp = player.getHealth();
        float max = Math.max(1f, player.getMaxHealth());
        float ratio = Math.max(0f, Math.min(1f, hp / max));
        if (hp < lastAtmoHp - 0.01f) {
            atmoDmgUntil = now + 460; // 掉血触发暗角脉冲
        }
        lastAtmoHp = hp;

        float flash = now < atmoDmgUntil ? (float) (atmoDmgUntil - now) / 460f : 0f;
        float low = ratio <= 0.25f ? (0.22f + 0.16f * (float) Math.sin(now / 280.0)) : 0f;
        float a = Math.max(flash, low);
        if (a <= 0.01f) {
            return;
        }
        int band = (int) (Math.min(sw, sh) * 0.16);
        int edge = argb(BfTheme.RED, (int) (a * 200));
        int edgeH = argb(BfTheme.RED, (int) (a * 140)); // 左右带半强度，避免四角过曝
        int clear = 0x00000000;
        // 上 / 下（纵向渐变）
        BfDraw.gradientV(ctx, 0, 0, sw, band, edge, clear);
        BfDraw.gradientV(ctx, 0, sh - band, sw, band, clear, edge);
        // 左 / 右（横向渐变）
        BfDraw.gradientH(ctx, 0, 0, band, sh, edgeH, clear);
        BfDraw.gradientH(ctx, sw - band, 0, band, sh, clear, edgeH);
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
        int lx = x + 14 + (int) Math.round(bobDx);
        int ly = y + 5 + (int) Math.round(bobDy);
        ctx.drawText(font, Text.literal("ATTACKERS  进攻方"), lx, ly, BfTheme.MUTED, false);
        String tk = String.valueOf(ClientMatchState.attackerTickets());
        ctx.drawText(font, Text.literal(tk), lx, ly + 11, 0xFFFFFFFF, true);
        ctx.drawText(font, Text.literal("部署剩余"), lx + font.getWidth(tk) + 6, ly + 15,
                BfTheme.FAINT, false);

        // 右侧：防守方得分（无票数制，仅显示击杀得分）
        int rightColW = 118;
        int rr = x + bw - 14 + (int) Math.round(bobDx);
        int ry = y + 5 + (int) Math.round(bobDy);
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
     * 左下血量卡（v6）：深底面板 + 1.8x 大数字 HP/MAX + GREEN 渐变细血条（白刻度）。
     * 受击红闪保留（血量下降触发边框红闪）；低血量红色呼吸。
     * 与右下武器文本、下方小地图竖向堆叠（统一左缘 LEFT_X）。
     */
    private void renderHealth(DrawContext ctx, TextRenderer font, int sw, int sh) {
        var player = MinecraftClient.getInstance().player;
        if (player == null) {
            return;
        }
        float hp = player.getHealth();
        float max = Math.max(1f, player.getMaxHealth());
        float ratio = Math.max(0f, Math.min(1f, hp / max));
        long now = System.currentTimeMillis();

        // 受击红闪：血量下降触发
        if (hp < lastHp - 0.01f) {
            dmgFlashUntil = now + 320;
        }
        lastHp = hp;
        boolean flash = now < dmgFlashUntil;

        int cardW = 196;
        int cardH = 40;
        int cx = LEFT_X + (int) Math.round(bobDx);
        int cy = sh - miniSize - BOTTOM - GAP - cardH + (int) Math.round(bobDy);

        // 面板
        BfDraw.fill(ctx, cx, cy, cardW, cardH, BfTheme.PANEL);
        BfDraw.border(ctx, cx, cy, cardW, cardH, flash ? BfTheme.RED : BfTheme.PANEL_LINE);
        ctx.fill(cx, cy, cx + cardW, cy + 1, 0x33FFFFFF); // 顶部高光

        // 左侧状态色条（低血量红呼吸，攻方占点语义不在此强调）
        int stateCol;
        if (ratio <= 0.25f) {
            stateCol = (now % 900) < 450 ? 0xFFF0483E : 0xFFFFB3AA;
        } else if (ratio <= 0.5f) {
            stateCol = 0xFFF2C94C;
        } else {
            stateCol = BfTheme.GREEN;
        }
        ctx.fill(cx, cy, cx + 2, cy + cardH, stateCol);

        // 1.8x 大数字 HP
        String hpText = String.valueOf((int) Math.ceil(hp));
        var ms = ctx.getMatrices();
        ms.push();
        ms.translate(cx + 18f, 0f, 0f);
        float big = 1.8f;
        ms.scale(big, big, 1f);
        ctx.drawText(font, Text.literal(hpText), 0, Math.round((cy + 2) / big), 0xFFFFFFFF, true);
        ms.pop();
        // /MAX 小字
        String maxText = "/" + (int) max;
        int numW = (int) Math.ceil(font.getWidth(hpText) * big);
        ctx.drawText(font, Text.literal(maxText), cx + 18 + numW + 7,
                cy + 15, ratio <= 0.25f ? stateCol : BfTheme.TEXT_DIM, true);

        // GREEN 渐变细血条 + 白刻度（每 25%）
        int barX = cx + 8;
        int barW = cardW - 16;
        int barY = cy + cardH - 7;
        int barH = 4;
        ctx.fill(barX, barY, barX + barW, barY + barH, 0xE0000000);
        int fillW = (int) (barW * ratio);
        if (fillW > 0) {
            BfDraw.gradientV(ctx, barX, barY, fillW, barH, BfTheme.GREEN, BfTheme.GREEN_DIM);
            ctx.fill(barX, barY, barX + fillW, barY + 1, 0xAAFFFFFF); // 顶高光
        }
        for (int t = 1; t <= 3; t++) {
            int tx = barX + (barW * t / 4);
            ctx.fill(tx, barY, tx + 1, barY + barH, 0x55FFFFFF); // 白刻度
        }
    }

    /**
     * 右下武器文本块（v6）：纯文字。
     * L1 武器名（TaCZ GunId 取 ':' 后段大写；非枪显示 FIST）
     * L2 弹匣 剩余 / 满容（数字 TEAL 高亮；满容拿不到则显示 '?'，非枪显示 '—'）
     * L3 备弹：背包内全部 tacz:ammo 物品 Count 之和（能匹配该枪 AmmoId 更佳）
     * 面板 PANEL 半透明 + 左 2px TEAL 强调 + BfGlow 微量。
     */
    private void renderWeapon(DrawContext ctx, TextRenderer font, int sw, int sh) {
        var player = MinecraftClient.getInstance().player;
        if (player == null) {
            return;
        }
        var stack = player.getMainHandStack();
        NbtCompound nbt = customNbt(stack);

        String gunId = (nbt != null) ? nbt.getString(NBT_GUN_ID) : "";
        boolean isGun = !gunId.isEmpty() && !stack.isEmpty();
        String name;
        if (isGun) {
            int c = gunId.indexOf(':');
            name = (c >= 0 ? gunId.substring(c + 1) : gunId).toUpperCase(java.util.Locale.ROOT);
        } else {
            name = "FIST";
        }

        int ammoNow = (nbt != null && nbt.contains(NBT_AMMO_NOW)) ? nbt.getInt(NBT_AMMO_NOW) : 0;
        Integer cap = isGun ? readMagCapacity(stack, gunId) : null;
        int reserve = readReserveAmmo(player);

        int pw = 210;
        int ph = 46;
        int px = sw - pw - 12 + (int) Math.round(bobDx);
        int py = sh - ph - 12 + (int) Math.round(bobDy);

        // 面板 + 左强调 + 微量辉光
        BfDraw.fill(ctx, px, py, pw, ph, BfTheme.PANEL);
        BfDraw.border(ctx, px, py, pw, ph, BfTheme.PANEL_LINE);
        ctx.fill(px, py, px + 2, py + ph, BfTheme.TEAL);
        BfGlow.rect(ctx, px, py, pw, ph, BfTheme.TEAL & 0xFFFFFF, 22, 5);

        ctx.drawText(font, Text.literal(name), px + 12, py + 7, BfTheme.TEXT, true);

        // L2 弹匣 剩余 / 满容
        String magNum = String.valueOf(ammoNow);
        String sep = " / ";
        String capStr = (cap != null) ? String.valueOf(cap) : (isGun ? "?" : "—");
        int lx2 = px + 12;
        int ly2 = py + 20;
        String label = "弹匣 ";
        ctx.drawText(font, Text.literal(label), lx2, ly2, BfTheme.MUTED, false);
        int w1 = font.getWidth(label);
        ctx.drawText(font, Text.literal(magNum), lx2 + w1, ly2, BfTheme.TEAL, true);
        int w2 = font.getWidth(magNum);
        ctx.drawText(font, Text.literal(sep), lx2 + w1 + w2, ly2, BfTheme.TEXT_DIM, false);
        int w3 = font.getWidth(sep);
        ctx.drawText(font, Text.literal(capStr), lx2 + w1 + w2 + w3, ly2, BfTheme.TEAL, true);

        // L3 备弹
        ctx.drawText(font, Text.literal("备弹：" + reserve), px + 12, py + 33, BfTheme.TEXT_DIM, false);
    }

    // ============ TaCZ 只读（反射，缺失即降级，绝不抛到渲染外） ============

    /** 弹匣满容：经 TaCZ GunData 取 magazine.ammoAmount（多方法名兜底）。拿不到返回 null。 */
    private static Integer readMagCapacity(ItemStack stack, String gunId) {
        Object gd = getGunData(stack, gunId);
        if (gd == null) {
            return null;
        }
        Object mag = invoke(gd, "getMagazine");
        if (mag == null) {
            mag = invoke(gd, "getAmmoData");
        }
        if (mag == null) {
            return null;
        }
        Object amt = invoke(mag, "getAmmoAmount");
        if (amt == null) {
            amt = invoke(mag, "getRoundedAmmoAmount");
        }
        if (amt instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    /** 该枪所需弹药 AmmoId（用于备弹按 AmmoId 匹配）。拿不到返回 null。 */
    private static String gunAmmoId(ItemStack stack, String gunId) {
        Object gd = getGunData(stack, gunId);
        if (gd == null) {
            return null;
        }
        Object id = invoke(gd, "getAmmoId");
        if (id instanceof String s && !s.isEmpty()) {
            return s;
        }
        return null;
    }

    /** 取 TaCZ GunData：优先 GunItem.getGunData(stack)，其次 GunData.fromId(gunId)。 */
    private static Object getGunData(ItemStack stack, String gunId) {
        try {
            Class<?> gi = Class.forName("cn.tacz.sp.item.GunItem");
            try {
                return gi.getMethod("getGunData", ItemStack.class).invoke(null, stack);
            } catch (NoSuchMethodException ignored) {
                // 某些版本用 GunData.fromId
            }
            return Class.forName("cn.tacz.sp.data.GunData").getMethod("fromId", String.class).invoke(null, gunId);
        } catch (Throwable t) {
            return null; // 类/方法不存在（如未装 TaCZ）→ 静默降级
        }
    }

    private static Object invoke(Object target, String name) {
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 背包内 tacz:ammo 物品 Count 之和；若已知该枪 AmmoId 则仅计匹配者。 */
    private static int readReserveAmmo(PlayerEntity player) {
        String want = null;
        var mh = player.getMainHandStack();
        NbtCompound mn = customNbt(mh);
        if (mn != null && !mn.getString(NBT_GUN_ID).isEmpty()) {
            want = gunAmmoId(mh, mn.getString(NBT_GUN_ID));
        }
        int total = 0;
        for (ItemStack s : player.getInventory().main) {
            total += ammoItemCount(s, want);
        }
        for (ItemStack s : player.getInventory().offHand) {
            total += ammoItemCount(s, want);
        }
        return total;
    }

    private static int ammoItemCount(ItemStack s, String wantAmmoId) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        Identifier id = Registries.ITEM.getId(s.getItem());
        if (!TACZ_AMMO_ITEM.equals(id.toString())) {
            return 0;
        }
        if (wantAmmoId != null) {
            NbtCompound nbt = customNbt(s);
            if (nbt != null) {
                String aid = nbt.getString("AmmoId");
                if (!aid.isEmpty() && !aid.equals(wantAmmoId)) {
                    return 0; // 不匹配该枪弹药
                }
            }
        }
        return s.getCount();
    }

    private static double clampAbs(double v, double cap) {
        return Math.max(-cap, Math.min(cap, v));
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

    /** 1.21.1 数据组件化读取物品自定义 NBT（TaCZ GunId/AmmoId 等经指令写入 custom_data）。
     *  兼容 TaCZ 旧直存与组件容器两种形态，读不到返回 null。 */
    private static NbtCompound customNbt(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        try {
            if (stack.contains(DataComponentTypes.CUSTOM_DATA)) {
                NbtCompound n = stack.get(DataComponentTypes.CUSTOM_DATA).copyNbt();
                if (n != null && !n.isEmpty()) {
                    return n;
                }
            }
            // 备用：直接找组件内的 NbtComponent（某些模组用其它键承载）
            for (var entry : stack.getComponents().stream().toList()) {
                if (entry.value() instanceof NbtComponent nc) {
                    NbtCompound c = nc.copyNbt();
                    if (c != null && !c.isEmpty()) {
                        return c;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 反射/组件异常静默降级
        }
        return null;
    }
}
