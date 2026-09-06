package com.breakfront.client.ui;

import com.breakfront.client.bf.BfDraw;
import com.breakfront.client.bf.BfGlow;
import com.breakfront.client.bf.BfTheme;
import com.breakfront.client.geo.GeoHttp;
import com.breakfront.client.geo.GeoSession;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Geekhonize 账号登录 / 注册屏（BREAKFRONT 矢量风格，无贴图）。
 *
 * <p>供离线 MC 玩家在进服前（主菜单）或游戏内（/geo ui）登录统一账号：
 * 直连 auth.geekhonize.top 注册/登录 → token 存本地 → 进服自动携带令牌与服务器绑定
 * （防自报名冒名）。已登录态显示账号并可退出/提示网页端改密。
 */
public class BfGeoLoginScreen extends Screen {

    private final Screen parent;

    // 输入态
    private String userBuf = "";
    private String passBuf = "";
    private int focus = 0;                 // 0 用户名 / 1 密码
    private boolean registerMode = false;  // 登录 or 注册
    private boolean busy = false;
    private boolean showPw = false;

    // 反馈
    private String msgText = "";
    private boolean msgOk = false;

    // 布局（每次 render 重算；鼠标命中复用）
    private int panelX, panelY, panelW, panelH;
    private int boxX, boxW, boxH, userBoxY, passBoxY;
    private int submitX, submitY, submitW, submitH;
    private int toggleX, toggleY, toggleW, toggleH;
    private int logoutX, logoutY, logoutW, logoutH;
    private int closeX, closeY, closeW, closeH;

    public BfGeoLoginScreen(Screen parent) {
        super(Text.literal("GEEKHONIZE 账号"));
        this.parent = parent;
        GeoSession.load();
        if (GeoSession.signedIn()) {
            userBuf = GeoSession.username();
        }
    }

    // ================= 渲染 =================

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int sw = this.width;
        int sh = this.height;
        BfDraw.gradientV(ctx, 0, 0, sw, sh, BfTheme.BG_DEEP, BfTheme.BG_UP);
        BfDraw.fill(ctx, 0, 0, sw, sh, BfTheme.SCREEN_DIM);
        layout();

        if (GeoSession.signedIn()) {
            renderSignedIn(ctx, mouseX, mouseY, sw);
        } else {
            renderForm(ctx, mouseX, mouseY, sw);
        }
    }

    private void layout() {
        int sw = this.width;
        int sh = this.height;
        panelW = Math.min(440, sw - 56);
        boolean si = GeoSession.signedIn();
        panelH = si ? 232 : 306;
        panelX = (sw - panelW) / 2;
        panelY = Math.max(24, (sh - panelH) / 2 - 20);

        boxX = panelX + 30;
        boxW = panelW - 60;
        boxH = 36;
        userBoxY = panelY + (si ? 0 : 96);
        passBoxY = userBoxY + boxH + 30;

        int bottomPad = si ? 40 : 62;
        submitW = Math.min(240, panelW - 60);
        submitH = 40;
        submitX = panelX + (panelW - submitW) / 2;
        submitY = panelY + panelH - bottomPad - submitH;

        toggleW = 200;
        toggleH = 22;
        toggleX = panelX + (panelW - toggleW) / 2;
        toggleY = submitY + submitH + 6;

        logoutW = Math.min(180, (panelW - 60) / 2);
        logoutH = 36;
        logoutX = panelX + 30;
        logoutY = panelY + panelH - 52 - logoutH;
        closeW = Math.min(140, (panelW - 60) / 2);
        closeX = panelX + panelW - 30 - closeW;
        closeY = logoutY;
    }

    private void renderForm(DrawContext ctx, int mouseX, int mouseY, int sw) {
        panel(ctx);
        // 标题
        int ty = panelY + 22;
        ctx.drawText(this.textRenderer, Text.literal("GEEKHONIZE 账号"),
                panelX + 30, ty, BfTheme.TEAL, false);
        ctx.drawText(this.textRenderer,
                Text.literal(registerMode ? "注册 Geekhonize 账号" : "登录后进服自动绑定 · 防冒名"),
                panelX + 30, ty + 14, BfTheme.MUTED, false);

        // 用户名框
        drawBox(ctx, "用户名", userBuf, userBoxY, focus == 0, mouseX, mouseY, false);
        // 密码框
        drawBox(ctx, registerMode ? "密码（6-128 位）" : "密码", passBuf, passBoxY,
                focus == 1, mouseX, mouseY, true);

        // 主按钮
        boolean hover = inRect(mouseX, mouseY, submitX, submitY, submitW, submitH);
        if (hover && !busy) {
            BfGlow.rect(ctx, submitX - 3, submitY - 3, submitW + 6, submitH + 6,
                    BfTheme.TEAL & 0xFFFFFF, 60, 7);
        }
        BfDraw.parallelogram(ctx, submitX, submitY, submitW, submitH, 6,
                busy ? BfTheme.PANEL_LINE : BfTheme.TEAL);
        String act = busy ? "处理中…" : (registerMode ? "注 册" : "登 录");
        int lw = this.textRenderer.getWidth(act);
        ctx.drawText(this.textRenderer, Text.literal(act),
                submitX + submitW / 2 - lw / 2 + 6,
                submitY + submitH / 2 - this.textRenderer.fontHeight / 2,
                busy ? BfTheme.MUTED : 0xFF0A0D12, false);

        // 切换 登录/注册
        String tg = registerMode ? "← 已有账号？返回登录" : "没有账号？注册一个（同时登录）";
        int gw = this.textRenderer.getWidth(tg);
        boolean gh = inRect(mouseX, mouseY, toggleX, toggleY, toggleW, toggleH);
        ctx.drawText(this.textRenderer, Text.literal(tg),
                panelX + (panelW - gw) / 2, toggleY + 4,
                gh ? BfTheme.TEAL : BfTheme.MUTED, false);

        // 反馈
        if (!msgText.isEmpty()) {
            ctx.drawText(this.textRenderer, Text.literal(msgText),
                    panelX + 30, toggleY + 24, msgOk ? BfTheme.GREEN : BfTheme.RED, false);
        }
    }

    private void renderSignedIn(DrawContext ctx, int mouseX, int mouseY, int sw) {
        panel(ctx);
        int ty = panelY + 24;
        ctx.drawText(this.textRenderer, Text.literal("已登录 Geekhonize"),
                panelX + 30, ty, BfTheme.GREEN, false);

        String u = GeoSession.username();
        int ux = panelX + 30;
        int uy = ty + 26;
        // 首字母方块
        BfDraw.diamond(ctx, ux + 12, uy + 8, 10, BfTheme.TEAL);
        ctx.drawText(this.textRenderer,
                Text.literal((u.isEmpty() ? "?" : u.substring(0, 1).toUpperCase())),
                ux + 12 - 3, uy + 2, 0xFF0A0D12, false);
        ctx.drawText(this.textRenderer, Text.literal(u.isEmpty() ? "(未知)" : u),
                ux + 34, uy + 4, BfTheme.TEXT, false);

        int infoY = uy + 26;
        ctx.drawText(this.textRenderer,
                Text.literal("进服后自动与服务器绑定 · 角色以服务端校验为准"),
                ux, infoY, BfTheme.MUTED, false);
        ctx.drawText(this.textRenderer,
                Text.literal("网页端改密 / 管理：auth.geekhonize.top"),
                ux, infoY + 14, BfTheme.FAINT, false);

        // 退出
        boolean lh = inRect(mouseX, mouseY, logoutX, logoutY, logoutW, logoutH);
        if (lh) {
            BfGlow.rect(ctx, logoutX - 2, logoutY - 2, logoutW + 4, logoutH + 4,
                    BfTheme.RED & 0xFFFFFF, 34, 5);
        }
        BfDraw.parallelogram(ctx, logoutX, logoutY, logoutW, logoutH, 5,
                0xE6161C25);
        BfDraw.border(ctx, logoutX, logoutY, logoutW, logoutH,
                lh ? BfTheme.RED_DIM : BfTheme.PANEL_LINE);
        String lb = "退出登录";
        int lbw = this.textRenderer.getWidth(lb);
        ctx.drawText(this.textRenderer, Text.literal(lb),
                logoutX + logoutW / 2 - lbw / 2,
                logoutY + logoutH / 2 - this.textRenderer.fontHeight / 2,
                lh ? BfTheme.RED : BfTheme.TEXT_DIM, false);

        // 关闭
        boolean ch = inRect(mouseX, mouseY, closeX, closeY, closeW, closeH);
        if (ch) {
            BfGlow.rect(ctx, closeX - 2, closeY - 2, closeW + 4, closeH + 4,
                    BfTheme.TEAL & 0xFFFFFF, 30, 5);
        }
        BfDraw.parallelogram(ctx, closeX, closeY, closeW, closeH, 5, BfTheme.TEAL);
        String cb = "关 闭";
        int cbw = this.textRenderer.getWidth(cb);
        ctx.drawText(this.textRenderer, Text.literal(cb),
                closeX + closeW / 2 - cbw / 2,
                closeY + closeH / 2 - this.textRenderer.fontHeight / 2, 0xFF0A0D12, false);

        if (!msgText.isEmpty()) {
            ctx.drawText(this.textRenderer, Text.literal(msgText),
                    panelX + 30, closeY + closeH + 8,
                    msgOk ? BfTheme.GREEN : BfTheme.RED, false);
        }
    }

    private void panel(DrawContext ctx) {
        BfDraw.fill(ctx, panelX, panelY, panelW, panelH, BfTheme.PANEL);
        BfDraw.fill(ctx, panelX, panelY, panelW, 3, BfTheme.TEAL);
        BfDraw.border(ctx, panelX, panelY, panelW, panelH, BfTheme.PANEL_LINE);
        // 右下角青点（装饰呼应）
        BfDraw.fill(ctx, panelX + panelW - 6, panelY + panelH - 6, 6, 6,
                0x2435E6D2);
    }

    private void drawBox(DrawContext ctx, String label, String val, int y,
                         boolean focused, int mx, int my, boolean secret) {
        ctx.drawText(this.textRenderer, Text.literal(label),
                boxX, y - 12, focused ? BfTheme.TEAL : BfTheme.MUTED, false);
        boolean hover = inRect(mx, my, boxX, y, boxW, boxH);
        if (focused || hover) {
            BfDraw.border(ctx, boxX - 1, y - 1, boxW + 2, boxH + 2,
                    focused ? BfTheme.TEAL : BfTheme.PANEL_LINE);
        } else {
            BfDraw.border(ctx, boxX, y, boxW, boxH, BfTheme.PANEL_LINE);
        }
        BfDraw.fill(ctx, boxX, y, boxW, boxH, 0xF00B121C);

        String shown = secret ? mask(val) : val;
        int ty = y + boxH / 2 - this.textRenderer.fontHeight / 2;
        ctx.drawText(this.textRenderer, Text.literal(shown), boxX + 10, ty,
                BfTheme.TEXT, false);
        if (focused && blinkOn()) {
            int tw = this.textRenderer.getWidth(shown);
            BfDraw.fill(ctx, boxX + 12 + tw, ty, 1, this.textRenderer.fontHeight,
                    BfTheme.TEAL);
        }
        // 输入上限/显示切换小字
        String hint = secret && !val.isEmpty() ? (showPw ? "隐藏" : "显示") : "";
        if (!hint.isEmpty()) {
            int hw = this.textRenderer.getWidth(hint);
            ctx.drawText(this.textRenderer, Text.literal(hint),
                    boxX + boxW - hw - 10, ty,
                    inRect(mx, my, boxX + boxW - 56, y, 46, boxH)
                            ? BfTheme.TEAL : BfTheme.FAINT, false);
        }
    }

    private static String mask(String s) {
        return "*".repeat(s.length());
    }

    private boolean blinkOn() {
        return (System.currentTimeMillis() / 500) % 2 == 0;
    }

    // ================= 交互 =================

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) {
            return false;
        }
        layout();
        if (GeoSession.signedIn()) {
            if (inRect(mx, my, logoutX, logoutY, logoutW, logoutH)) {
                doLogout();
                return true;
            }
            if (inRect(mx, my, closeX, closeY, closeW, closeH)) {
                close();
                return true;
            }
            return false;
        }
        // 输入框聚焦
        if (inRect(mx, my, boxX, userBoxY, boxW, boxH)) {
            focus = 0;
            return true;
        }
        if (inRect(mx, my, boxX, passBoxY, boxW, boxH)) {
            focus = 1;
            return true;
        }
        // 密码可见切换
        if (focus == 1 && !passBuf.isEmpty()
                && inRect(mx, my, boxX + boxW - 56, passBoxY, 46, boxH)) {
            showPw = !showPw;
            return true;
        }
        if (inRect(mx, my, submitX, submitY, submitW, submitH)) {
            submit();
            return true;
        }
        if (inRect(mx, my, toggleX, toggleY, toggleW, toggleH)) {
            registerMode = !registerMode;
            msgText = "";
            focus = 0;
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (GeoSession.signedIn()) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            focus = 1 - focus;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            if (focus == 0 && !userBuf.isEmpty()) {
                userBuf = userBuf.substring(0, userBuf.length() - 1);
            } else if (focus == 1 && !passBuf.isEmpty()) {
                passBuf = passBuf.substring(0, passBuf.length() - 1);
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            submit();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (GeoSession.signedIn() || busy) {
            return false;
        }
        if (chr == '\r' || chr == '\n' || chr < ' ') {
            return false;
        }
        if (focus == 0 && userBuf.length() < 32) {
            userBuf += chr;
            return true;
        }
        if (focus == 1 && passBuf.length() < 128) {
            passBuf += chr;
            return true;
        }
        return false;
    }

    private void submit() {
        if (busy) {
            return;
        }
        String u = userBuf.trim();
        String p = passBuf;
        if (u.isEmpty() || p.isEmpty()) {
            msgText = "请输入用户名与密码";
            msgOk = false;
            return;
        }
        busy = true;
        msgText = "";
        final boolean reg = registerMode;
        java.util.concurrent.CompletableFuture.supplyAsync(() -> reg
                ? GeoHttp.register(u, p, u)
                : GeoHttp.login(u, p))
                .thenAccept(r -> this.client.execute(() -> {
                    busy = false;
                    if (r.ok()) {
                        GeoSession.save(r.token(), r.username());
                        msgText = (reg ? "注册并登录成功：" : "登录成功：") + r.username();
                        msgOk = true;
                        // 已在服务器内 → 立即绑定（与 JOIN 自动绑定同通道）
                        if (this.client.getNetworkHandler() != null) {
                            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
                                    .send(new com.breakfront.net.AuthLoginPayload(r.token()));
                        }
                        // 稍后自动切入「已登录」视图（后台计时，不阻塞渲染线程）
                        Thread t = new Thread(() -> {
                            try {
                                Thread.sleep(900);
                            } catch (InterruptedException ignored) {
                            }
                            this.client.execute(() -> {
                                if (this.client.currentScreen == BfGeoLoginScreen.this) {
                                    GeoSession.load();
                                    msgText = "";
                                }
                            });
                        });
                        t.setDaemon(true);
                        t.start();
                    } else {
                        msgText = r.msg() == null || r.msg().isEmpty() ? "登录失败" : r.msg();
                        msgOk = false;
                    }
                }));
    }

    private void doLogout() {
        GeoSession.clear();
        if (this.client.getNetworkHandler() != null) {
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
                    .send(new com.breakfront.net.AuthLoginPayload(""));
        }
        userBuf = "";
        passBuf = "";
        registerMode = false;
        msgText = "已退出登录";
        msgOk = true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void close() {
        if (parent != null) {
            this.client.setScreen(parent);
        } else {
            this.client.setScreen(null);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ================= 工具 =================

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
}
