package com.breakfront.client.ui.admin;

import com.breakfront.client.bf.BfDraw;
import com.breakfront.client.bf.BfTheme;
import com.breakfront.client.state.ClientMatchState;
import com.breakfront.client.state.ClientMatchState.ZoneView;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * M8 管理员管控面板（Ctrl+Shift+F8 开关；需先通过门禁校验）。
 *
 * 左侧：当前扇区据点实时列表（字母/id/归属/进度/半径/坐标/距离，与 HUD 同源数据）。
 * 右侧：动作按钮——全部以玩家身份合成 /bfs 或 /bf admin 命令由服务端执行
 *       （权限 gate：op2 或管理员会话，零新协议成本）。
 * 覆盖半透明层但不暂停游戏，随时可看世界对照。
 */
public class BfAdminPanel extends Screen {

    private record Button(String label, String cmd, boolean enabled,
                          int x, int y, int w, int h) {
    }

    private final List<Button> buttons = new ArrayList<>();
    private int sel = -1;
    private int listX, listY, listW, listRowH = 30;
    private boolean previewOn;

    public BfAdminPanel() {
        super(Text.literal("ADMIN CONTROL"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 屏内再次 Ctrl+Shift+F8 → 收起面板
        if (keyCode == GLFW.GLFW_KEY_F8
                && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0
                && (modifiers & GLFW.GLFW_MOD_SHIFT) != 0) {
            if (this.client != null) {
                this.client.setScreen(null);
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int sw = this.width;
        int sh = this.height;
        buttons.clear();

        // —— 顶部品牌条（保留上方视野观察世界）——
        int top = 8;
        BfDraw.fill(ctx, 8, top, sw - 16, 34, 0xE61A222D);
        BfDraw.border(ctx, 8, top, sw - 16, 34, BfTheme.PANEL_LINE);
        BfDraw.fill(ctx, 8, top, 12, 34, BfTheme.YELLOW);
        ctx.drawText(this.textRenderer, Text.literal("ADMIN CONTROL  管理员管控"),
                28, top + 8, BfTheme.YELLOW, false);
        String hint = "Ctrl+Shift+F8 收起  ·  ESC 关闭  ·  操作以你的身份经 /bfs 执行";
        int hw = this.textRenderer.getWidth(hint);
        ctx.drawText(this.textRenderer, Text.literal(hint),
                sw - hw - 20, top + 11, BfTheme.MUTED, false);

        int px = 16;
        int py = top + 48;
        List<ZoneView> zones = ClientMatchState.zones();

        // —— 左侧：据点列表 ——
        listX = px;
        listY = py;
        listW = Math.min(sw - 300, 440);
        listW = Math.max(listW, 300);
        int listH = Math.min(sh - py - 12, 30 + Math.max(1, zones.size()) * listRowH + 14);
        BfDraw.fill(ctx, listX, listY, listW, listH, 0xCC12171F);
        BfDraw.border(ctx, listX, listY, listW, listH, BfTheme.PANEL_LINE);
        ctx.drawText(this.textRenderer, Text.literal("据点列表  ·  SECTOR "
                        + (ClientMatchState.sectorIndex() + 1) + "/" + ClientMatchState.sectorCount()),
                listX + 14, listY + 8, BfTheme.TEXT_DIM, false);
        if (zones.isEmpty()) {
            ctx.drawText(this.textRenderer,
                    Text.literal("（当前扇区无据点 —— 进服后用 /bfs on + 准星 /bfs here 圈点）"),
                    listX + 14, listY + 32, BfTheme.MUTED, false);
        }
        double pX = this.client != null && this.client.player != null ? this.client.player.getX() : 0;
        double pZ = this.client != null && this.client.player != null ? this.client.player.getZ() : 0;
        int ry0 = listY + 24;
        for (int i = 0; i < zones.size(); i++) {
            ZoneView z = zones.get(i);
            int ry = ry0 + i * listRowH;
            boolean isSel = i == sel;
            if (isSel) {
                ctx.fill(listX + 4, ry, listX + listW - 4, ry + listRowH - 2, 0x38F5D44A);
            }
            boolean attacker = z.ownerOrdinal() == 0;
            BfDraw.diamond(ctx, listX + 22, ry + listRowH / 2 - 1, 7,
                    attacker ? BfTheme.YELLOW : BfTheme.BLUE);
            ctx.drawText(this.textRenderer, Text.literal(z.letter()),
                    listX + 17, ry + 8, 0xFF0A0D12, false);
            String state = attacker ? "已占"
                    : (z.meter() > 1e-3f ? "争夺 " + (int) (z.meter() * 100) + "%" : "防守");
            ctx.drawText(this.textRenderer,
                    Text.literal(z.zoneId() + "  " + state + "  r=" + (int) z.radius()),
                    listX + 40, ry + 8,
                    attacker ? BfTheme.TEXT : BfTheme.TEXT_DIM, false);
            double dist = Math.sqrt((z.worldX() - pX) * (z.worldX() - pX)
                    + (z.worldZ() - pZ) * (z.worldZ() - pZ));
            String right = String.format("%.0f,%.0f  %dm", z.worldX(), z.worldZ(), (int) dist);
            int rw = this.textRenderer.getWidth(right);
            ctx.drawText(this.textRenderer, Text.literal(right),
                    listX + listW - rw - 14, ry + 8, BfTheme.FAINT, false);
            ctx.fill(listX + 10, ry + listRowH - 2, listX + listW - 10,
                    ry + listRowH - 1, 0x1AFFFFFF);
        }

        // —— 右侧：动作区 ——
        int ax = listX + listW + 14;
        int aw = Math.min(sw - ax - 16, 260);
        aw = Math.max(aw, 210);
        int btnH = 24;
        int gap = 6;
        ZoneView sv = sel >= 0 && sel < zones.size() ? zones.get(sel) : null;
        String selId = sv == null ? "" : sv.zoneId();

        List<String[]> actions = new ArrayList<>();
        actions.add(new String[]{"传送到此点", sv == null ? "" : "/bf admin goto " + selId});
        actions.add(new String[]{"移到准星位置", sv == null ? "" : "/bfs move " + selId});
        actions.add(new String[]{"半径 +4", sv == null ? ""
                : "/bfs resize " + selId + " " + Math.min(64, (int) sv.radius() + 4)});
        actions.add(new String[]{"半径 -4", sv == null ? ""
                : "/bfs resize " + selId + " " + Math.max(1, (int) sv.radius() - 4)});
        actions.add(new String[]{"删除该点", sv == null ? "" : "/bfs remove " + selId});
        actions.add(new String[]{"撤销上一步", "/bfs undo"});
        actions.add(new String[]{previewOn ? "地面预览：开（点击关闭）" : "地面预览：关（点击开启）",
                previewOn ? "/bfs off" : "/bfs on"});
        actions.add(new String[]{"保存布局  SAVE", "/bfs save"});
        actions.add(new String[]{"应用布局  APPLY", "/bfs apply"});
        actions.add(new String[]{"注销管理员", ""}); // 仅本地关闭会话显示

        int totalH = actions.size() * btnH + (actions.size() - 1) * gap + 20;
        int ay = py;
        BfDraw.fill(ctx, ax, ay, aw, totalH, 0xCC12171F);
        BfDraw.border(ctx, ax, ay, aw, totalH, BfTheme.PANEL_LINE);
        ctx.drawText(this.textRenderer, Text.literal("操作"),
                ax + 12, ay + 7, BfTheme.TEXT_DIM, false);
        int by = ay + 18;
        int bw = aw - 24;
        int bx = ax + 12;
        for (int i = 0; i < actions.size(); i++) {
            String label = actions.get(i)[0];
            String cmd = actions.get(i)[1];
            if (label.equals("注销管理员")) {
                cmd = "/bf admin logout";
            }
            boolean enabled = !cmd.isEmpty();
            boolean hover = mouseInside(mouseX, mouseY, bx, by, bw, btnH);
            BfDraw.fill(ctx, bx, by, bw, btnH, enabled
                    ? (hover ? 0xFF3A4A63 : 0xFF232C3A) : 0xFF151B24);
            if (enabled) {
                BfDraw.border(ctx, bx, by, bw, btnH,
                        hover ? BfTheme.BLUE : BfTheme.PANEL_LINE);
            }
            int col = enabled ? (hover ? 0xFFFFFFFF : 0xFFE6EBF2) : 0xFF5A6472;
            int lw = this.textRenderer.getWidth(label);
            ctx.drawText(this.textRenderer, Text.literal(label),
                    bx + (bw - lw) / 2, by + (btnH - 8) / 2, col, false);
            buttons.add(new Button(label, cmd, enabled, bx, by, bw, btnH));
            by += btnH + gap;
        }

        // 选中详情行（操作区正下方）
        if (sv != null) {
            String d = String.format("已选 %s · %s%s · meter %.0f%% · r=%.0f · 距你 %dm",
                    sv.zoneId(),
                    sv.ownerOrdinal() == 0 ? "攻方 " : "守方 ",
                    sv.ownerOrdinal() == 0 ? "已占领" : (sv.meter() > 1e-3f ? "争夺中" : "防守中"),
                    sv.meter() * 100, sv.radius(),
                    (int) Math.sqrt((sv.worldX() - pX) * (sv.worldX() - pX)
                            + (sv.worldZ() - pZ) * (sv.worldZ() - pZ)));
            ctx.drawText(this.textRenderer, Text.literal(d),
                    ax + 12, by + 6, BfTheme.YELLOW, false);
        }

        super.render(ctx, mouseX, mouseY, delta);
    }

    private boolean mouseInside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (Button b : buttons) {
                if (mouseInside(mouseX, mouseY, b.x(), b.y(), b.w(), b.h())) {
                    if (b.enabled() && !b.cmd().isEmpty()) {
                        run(b.cmd());
                        if (b.label().startsWith("地面预览")) {
                            previewOn = !previewOn;
                        }
                        if (b.label().equals("注销管理员")) {
                            com.breakfront.client.state.AdminState.logoutLocal();
                            if (this.client != null) {
                                this.client.setScreen(null);
                            }
                            return true;
                        }
                    }
                    return true;
                }
            }
            // 据点列表行选择
            List<ZoneView> zones = ClientMatchState.zones();
            if (mouseX >= listX && mouseX <= listX + listW
                    && mouseY >= listY + 24 && mouseY < listY + 24 + zones.size() * listRowH) {
                int i = (int) ((mouseY - (listY + 24)) / listRowH);
                if (i >= 0 && i < zones.size()) {
                    sel = i;
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 以玩家身份向服务端发命令（不带前导斜杠；gate 由服务端判定）。 */
    private void run(String command) {
        if (this.client != null && this.client.getNetworkHandler() != null) {
            this.client.getNetworkHandler().sendChatCommand(command);
        }
    }
}
