package com.breakfront.client.ui.vanilla;

import com.breakfront.client.bf.BfDraw;
import com.breakfront.client.bf.BfEasing;
import com.breakfront.client.bf.BfGlow;
import com.breakfront.client.bf.BfTheme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * BREAKFRONT 暂停屏（替换原版 GameMenuScreen / PauseScreen）。
 *
 * 视觉：全屏深色底（BG_DEEP → BG_UP 渐变 + SCREEN_DIM 蒙层）+ 顶部 BREAKFRONT
 * 标题与副标题「GAME PAUSED 暂停」+ 居中的行动卡片（BF 平行四边形按钮）。
 *
 * 按钮（自上而下）：
 *   1. 返回游戏       —— 主行动：TEAL 填充 + 辉光，回游戏（setScreen(null)）
 *   2. 选项…         —— 打开原版 OptionsScreen（parent = this，可回跳）
 *   3. 返回主菜单     —— disconnect 回 TitleScreen（由 tick 处理器接管为 BF 主菜单）
 *   4. 退出游戏       —— 危险操作：RED 弱化，scheduleStop()
 *
 * 交互：鼠标 hover 高亮并选中；键盘 ↑/↓ 选择、Enter 确认；Esc 关闭（返回游戏）。
 * 全部走几何矢量绘制（BfDraw/BfGlow）+ 文本，无贴图。
 */
public class BfPauseScreen extends Screen {

    /** 按钮样式。 */
    private enum Style { PRIMARY, SECONDARY, DANGER }

    /** 单个可点按钮。 */
    private static final class Btn {
        final String label;
        final Style style;
        int x, y, w, h;
        float hover;

        Btn(String label, Style style) {
            this.label = label;
            this.style = style;
        }
    }

    private final List<Btn> buttons = new ArrayList<>();
    private int sel = 0;

    // 布局（render 时按窗口尺寸计算，兼容 resize）
    private static final int SLANT = 6;

    public BfPauseScreen(Screen parent) {
        super(Text.literal("BREAKFRONT · PAUSED"));
        buttons.add(new Btn("返回游戏", Style.PRIMARY));
        buttons.add(new Btn("选项…", Style.SECONDARY));
        buttons.add(new Btn("返回主菜单", Style.SECONDARY));
        buttons.add(new Btn("退出游戏", Style.DANGER));
    }

    // ================= 渲染 =================

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int sw = this.width;
        int sh = this.height;

        drawBackground(ctx, sw, sh);

        // 顶部标题 + 副标题
        drawHeader(ctx, sw, sh);

        // 布局与命中测试
        layout(sw, sh);
        // 鼠标 hover 选中
        for (int i = 0; i < buttons.size(); i++) {
            Btn b = buttons.get(i);
            if (inRect(mouseX, mouseY, b.x, b.y, b.w + SLANT, b.h)) {
                sel = i;
            }
        }

        // 行动卡片面板
        drawPanel(ctx, sw, sh);
        for (int i = 0; i < buttons.size(); i++) {
            drawButton(ctx, buttons.get(i), i == sel, delta);
        }

        drawFooter(ctx, sw, sh);
    }

    private void drawBackground(DrawContext ctx, int sw, int sh) {
        BfDraw.gradientV(ctx, 0, 0, sw, sh, BfTheme.BG_DEEP, BfTheme.BG_UP);
        // 全屏蒙层（压暗后方游戏画面）
        BfDraw.fill(ctx, 0, 0, sw, sh, BfTheme.SCREEN_DIM);
        // 地平线光带（呼应主菜单战场氛围）
        int horizon = (int) (sh * 0.82);
        BfDraw.fill(ctx, 0, horizon, sw, 1, 0x22FFFFFF);
    }

    private void drawHeader(DrawContext ctx, int sw, int sh) {
        int titleY = (int) (sh * 0.18);
        String title = "BREAKFRONT";
        int titleW = this.textRenderer.getWidth(title);
        float s = 1.7f;

        // 字标左侧菱形辉光标（位于标题左缘左侧）
        int leftEdge = sw / 2 - (int) (titleW * s / 2);
        int diaX = leftEdge - 26;
        BfGlow.rect(ctx, diaX - 8, titleY + (int) (10 * s) - 8, 16, 16,
                BfTheme.TEAL & 0xFFFFFF, 50, 6);
        BfDraw.diamond(ctx, diaX, titleY + (int) (10 * s), 6.5, BfTheme.TEAL);

        var ms = ctx.getMatrices();
        ms.push();
        ms.translate(sw / 2, titleY, 0);
        ms.scale(s, s, 1);
        ctx.drawText(this.textRenderer, Text.literal(title),
                -titleW / 2, 0, BfTheme.TEAL, false);
        ms.pop();

        // 副标题
        String sub = "GAME PAUSED   暂停";
        int subW = this.textRenderer.getWidth(sub);
        ctx.drawText(this.textRenderer, Text.literal(sub),
                sw / 2 - subW / 2, titleY + (int) (22 * s), BfTheme.MUTED, false);
    }

    private void layout(int sw, int sh) {
        int btnW = Math.min(320, sw - 80);
        int btnH = 42;
        int gap = 12;
        int panelW = btnW + 56;

        int listH = buttons.size() * btnH + (buttons.size() - 1) * gap;
        int panelH = 24 + 64 + 16 + listH + 28; // 上padding + 标题区 + 间距 + 列表 + 底hint
        int panelX = (sw - panelW) / 2;
        int panelY = Math.max((int) (sh * 0.30), sh / 2 - panelH / 2);

        int listX = panelX + 28;
        int listY = panelY + 24 + 64 + 16;
        for (int i = 0; i < buttons.size(); i++) {
            Btn b = buttons.get(i);
            b.x = listX;
            b.y = listY + i * (btnH + gap);
            b.w = btnW;
            b.h = btnH;
        }
    }

    private void drawPanel(DrawContext ctx, int sw, int sh) {
        int btnW = Math.min(320, sw - 80);
        int btnH = 42;
        int gap = 12;
        int panelW = btnW + 56;
        int listH = buttons.size() * btnH + (buttons.size() - 1) * gap;
        int panelH = 24 + 64 + 16 + listH + 28;
        int panelX = (sw - panelW) / 2;
        int panelY = Math.max((int) (sh * 0.30), sh / 2 - panelH / 2);

        // 面板底 + 顶部强调条
        BfDraw.fill(ctx, panelX, panelY, panelW, panelH, BfTheme.PANEL);
        BfDraw.fill(ctx, panelX, panelY, panelW, 3, BfTheme.TEAL);
        BfDraw.border(ctx, panelX, panelY, panelW, panelH, BfTheme.PANEL_LINE);

        // 面板内标题（小字）
        ctx.drawText(this.textRenderer, Text.literal("行动  ACTION"),
                panelX + 28, panelY + 30, BfTheme.MUTED, false);
    }

    private void drawButton(DrawContext ctx, Btn b, boolean selected, float delta) {
        boolean danger = b.style == Style.DANGER;
        boolean primary = b.style == Style.PRIMARY;

        b.hover = smooth(b.hover, (selected ? 1 : 0), delta);
        double h = BfEasing.easeOutCubic(b.hover);
        int x = b.x, y = b.y, w = b.w, hh = b.h;

        if (primary) {
            BfGlow.rect(ctx, x - 3, y - 3, w + 6, hh + 6, BfTheme.TEAL & 0xFFFFFF,
                    (int) (58 + 26 * h), 7);
            BfDraw.parallelogram(ctx, x, y, w, hh, SLANT, BfTheme.TEAL);
            BfDraw.parallelogram(ctx, x, y, w, hh, SLANT, argb(0xFFFFFFFF, (int) (70 * h)));
        } else if (danger) {
            if (h > 0.03) {
                BfGlow.rect(ctx, x - 2, y - 2, w + 4, hh + 4, BfTheme.RED & 0xFFFFFF,
                        (int) (34 * h), 5);
            }
            BfDraw.parallelogram(ctx, x, y, w, hh, SLANT, argb(BfTheme.PANEL, 255));
            BfDraw.parallelogram(ctx, x, y, w, hh, SLANT, argb(BfTheme.RED_DIM, (int) (120 * h)));
        } else {
            if (h > 0.03) {
                BfGlow.rect(ctx, x - 2, y - 2, w + 4, hh + 4, BfTheme.TEAL & 0xFFFFFF,
                        (int) (40 * h), 5);
            }
            BfDraw.parallelogram(ctx, x, y, w, hh, SLANT, argb(0xE6161C25, 255));
            BfDraw.parallelogram(ctx, x, y, w, hh, SLANT, argb(BfTheme.TEAL_DIM, (int) (90 * h)));
        }

        // 描边
        int borderCol = primary ? BfTheme.TEAL
                : (danger ? BfTheme.RED_DIM
                : argb(BfTheme.TEAL_DIM, (int) (150 + 105 * h)));
        BfDraw.border(ctx, x, y, w, hh, borderCol);

        // 选中：左侧强调条 + 辉光
        if (selected) {
            BfGlow.rect(ctx, x - 7, y + 4, 4, hh - 8, BfTheme.TEAL & 0xFFFFFF, 50, 5);
            BfDraw.fill(ctx, x - 2, y + 6, 3, hh - 12, BfTheme.TEAL);
        }

        // 标签
        int baseColor = primary ? 0xFF0A0D12 : (danger ? BfTheme.RED : BfTheme.TEXT);
        int lw = this.textRenderer.getWidth(b.label);
        int tx = x + w / 2 - lw / 2 + SLANT / 2;
        int ty = y + hh / 2 - this.textRenderer.fontHeight / 2;
        ctx.drawText(this.textRenderer, Text.literal(b.label), tx, ty, baseColor, false);
    }

    private void drawFooter(DrawContext ctx, int sw, int sh) {
        String hint = "↑ / ↓  选择      ENTER  确认      ESC  返回游戏";
        int hw = this.textRenderer.getWidth(hint);
        ctx.drawText(this.textRenderer, Text.literal(hint),
                sw / 2 - hw / 2, sh - 30, BfTheme.FAINT, false);
        String tag = "BREAKFRONT · BF2042 UI";
        int tw = this.textRenderer.getWidth(tag);
        ctx.drawText(this.textRenderer, Text.literal(tag),
                sw - tw - 16, sh - 22, BfTheme.FAINT, false);
    }

    // ================= 交互 =================

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) {
            return false;
        }
        for (int i = 0; i < buttons.size(); i++) {
            Btn b = buttons.get(i);
            if (inRect(mx, my, b.x, b.y, b.w + SLANT, b.h)) {
                sel = i;
                activate(i);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_UP) {
            sel = (sel - 1 + buttons.size()) % buttons.size();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DOWN) {
            sel = (sel + 1) % buttons.size();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            activate(sel);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void activate(int i) {
        switch (i) {
            case 0 -> client.setScreen(null);                       // 返回游戏
            case 1 -> client.setScreen(new OptionsScreen(this, client.options)); // 选项…
            case 2 -> toTitle();                                    // 返回主菜单
            case 3 -> client.scheduleStop();                        // 退出游戏
            default -> {
            }
        }
    }

    /** 断开当前世界/连接并回到标题屏（由 tick 处理器接管为 BF 主菜单）。
     *  与原版 GameMenuScreen「Save and Quit to Title」保持一致的两段式调用。 */
    private void toTitle() {
        if (client.world != null) {
            client.world.disconnect();
        }
        client.disconnect(new TitleScreen());
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void close() {
        client.setScreen(null); // Esc：返回游戏
    }

    // ================= 工具 =================

    private float smooth(float cur, float target, float delta) {
        float k = (float) Math.min(1, delta * 14);
        return cur + (target - cur) * k;
    }

    private static boolean inRect(double mx, double my, double x, double y, double w, double h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static int argb(int rgb, int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        return (a << 24) | (rgb & 0xFFFFFF);
    }
}
