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
 * <p>登录：直连 auth.geekhonize.top；注册：需邮箱 + 验证码（可一键发送，stub 联调自动回填）。
 * token 存本地（GeoSession），进服自动携带令牌与服务器绑定（防自报名冒名）。
 */
public class BfGeoLoginScreen extends Screen {

    private final Screen parent;

    // 输入态
    private String userBuf = "";
    private String passBuf = "";
    private String emailBuf = "";
    private String codeBuf = "";
    private int focus = 0;                 // 0 用户名 / 1 密码 / 2 邮箱 / 3 验证码
    private boolean registerMode = false;
    /** 令牌登录模式：粘贴「账号中心 → 游戏令牌」生成的长期令牌直接完成绑定。 */
    private boolean tokenMode = false;
    /** 令牌输入缓冲（tokenMode 下由 focus==0 编辑）。 */
    private String tokenBuf = "";
    private boolean busy = false;
    private boolean showPw = false;

    // 验证码发送倒计时（毫秒时间戳，0=可发送）
    private long sendCdUntil = 0;
    private boolean sendBusy = false;

    // 设备码登录（PCL/FCL 等第三方启动器：跳浏览器授权）
    private String deviceCode = "";
    private boolean devicePolling = false;

    // 反馈
    private String msgText = "";
    private boolean msgOk = false;

    // 布局（每次 render 重算；鼠标命中复用）
    private int panelX, panelY, panelW, panelH;
    private int boxX, boxW, boxH;
    private final int[] boxY = new int[4];
    private int rows;
    private int submitX, submitY, submitW, submitH;
    private int toggleX, toggleY, toggleW, toggleH;
    private int sendX, sendY, sendW, sendH;
    private int deviceX, deviceY, deviceW, deviceH;
    private int tokenX, tokenY, tokenW, tokenH;
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
            renderSignedIn(ctx, mouseX, mouseY);
        } else {
            renderForm(ctx, mouseX, mouseY);
        }
    }

    private void layout() {
        int sw = this.width;
        int sh = this.height;
        panelW = Math.min(460, sw - 56);
        boolean si = GeoSession.signedIn();
        rows = (si || !registerMode) ? (tokenMode ? 1 : 2) : 4;
        // 登录模式多一行「浏览器登录」按钮；注册模式比原版多留反馈区余量
        panelH = si ? 232 : (registerMode ? 448 : 402);
        panelX = (sw - panelW) / 2;
        panelY = Math.max(24, (sh - panelH) / 2 - 16);

        boxX = panelX + 30;
        boxW = panelW - 60;
        boxH = 36;
        int baseY = si ? panelY : panelY + 88;
        for (int i = 0; i < rows; i++) {
            boxY[i] = baseY + i * 62;
        }

        submitW = Math.min(240, panelW - 60);
        submitH = 40;
        submitX = panelX + (panelW - submitW) / 2;
        int rowsEnd = si ? panelY : (rows > 0 ? boxY[rows - 1] + boxH : panelY);
        submitY = Math.max(si ? panelY : rowsEnd + 30, panelY + panelH - 96);

        toggleW = 220;
        toggleH = 22;
        toggleX = panelX + (panelW - toggleW) / 2;
        toggleY = submitY + submitH + 6;

        // 登录辅助行（仅登录模式显示）：左=浏览器登录，右=令牌登录。
        // 两个按钮**并排共用原有的一行高度** —— 本面板的纵向布局是「以底边为基准往上
        // 推」的写法，再加一行会把面板撑破，故只能横向分摊宽度。
        int auxW = Math.min(320, panelW - 60);
        int auxGap = 8;
        deviceW = (auxW - auxGap) / 2;
        deviceH = 24;
        deviceX = panelX + (panelW - auxW) / 2;
        deviceY = toggleY + toggleH + 10;
        tokenW = deviceW;
        tokenH = deviceH;
        tokenX = deviceX + deviceW + auxGap;
        tokenY = deviceY;

        // 发送验证码（仅注册模式，位于验证码行内右侧）
        sendW = 118;
        sendH = 26;
        sendX = panelX + panelW - 30 - sendW;
        sendY = (registerMode && rows == 4) ? boxY[3] + boxH - sendH - 5 : 0;

        logoutW = Math.min(180, (panelW - 60) / 2);
        logoutH = 36;
        logoutX = panelX + 30;
        logoutY = panelY + panelH - 52 - logoutH;
        closeW = Math.min(140, (panelW - 60) / 2);
        closeX = panelX + panelW - 30 - closeW;
        closeY = logoutY;
    }

    private void renderForm(DrawContext ctx, int mouseX, int mouseY) {
        panel(ctx);
        int ty = panelY + 20;
        ctx.drawText(this.textRenderer, Text.literal("GEEKHONIZE 账号"),
                panelX + 30, ty, BfTheme.TEAL, false);
        ctx.drawText(this.textRenderer,
                Text.literal(registerMode ? "邮箱验证码注册（一个账号通行全部作品）"
                        : tokenMode ? "粘贴账号中心生成的「游戏令牌」即可完成绑定"
                        : "登录后进服自动绑定 · 防冒名"),
                panelX + 30, ty + 14, BfTheme.MUTED, false);

        if (tokenMode) {
            drawBox(ctx, "游戏令牌（账号中心 → 游戏令牌 生成）", tokenBuf, boxY[0],
                    focus == 0, mouseX, mouseY, false, 2048);
        } else {
            drawBox(ctx, "用户名", userBuf, boxY[0], focus == 0, mouseX, mouseY, false, 32);
            drawBox(ctx, registerMode ? "密码（6-128 位）" : "密码", passBuf, boxY[1],
                    focus == 1, mouseX, mouseY, true, 128);
        }
        if (rows == 4) {
            drawBox(ctx, "邮箱（接收验证码）", emailBuf, boxY[2],
                    focus == 2, mouseX, mouseY, false, 160);
            // 验证码框收窄给发送按钮让位
            int cdX = boxX;
            int cdW = boxW - sendW - 10;
            ctx.drawText(this.textRenderer, Text.literal("邮箱验证码"),
                    boxX, boxY[3] - 12, focus == 3 ? BfTheme.TEAL : BfTheme.MUTED, false);
            boolean fov = inRect(mouseX, mouseY, cdX, boxY[3], cdW, boxH);
            BfDraw.border(ctx, cdX - (fov || focus == 3 ? 1 : 0), boxY[3] - (fov || focus == 3 ? 1 : 0),
                    cdW + (fov || focus == 3 ? 2 : 0), boxH + (fov || focus == 3 ? 2 : 0),
                    (focus == 3 ? BfTheme.TEAL : (fov ? BfTheme.PANEL_LINE : BfTheme.PANEL_LINE)));
            BfDraw.fill(ctx, cdX, boxY[3], cdW, boxH, 0xF00B121C);
            String shown = codeBuf;
            int tyy = boxY[3] + boxH / 2 - this.textRenderer.fontHeight / 2;
            ctx.drawText(this.textRenderer, Text.literal(shown), cdX + 10, tyy,
                    BfTheme.TEXT, false);
            if (focus == 3 && blinkOn()) {
                int tw = this.textRenderer.getWidth(shown);
                BfDraw.fill(ctx, cdX + 12 + tw, tyy, 1, this.textRenderer.fontHeight, BfTheme.TEAL);
            }
            // 发送验证码按钮 / 倒计时
            long left = (sendCdUntil - System.currentTimeMillis() + 999) / 1000;
            boolean canSend = left <= 0 && !sendBusy && !busy;
            boolean shov = inRect(mouseX, mouseY, sendX, sendY, sendW, sendH);
            String lb = sendBusy ? "发送中…" : (left > 0 ? "重新发送(" + left + "s)" : "发送验证码");
            if (canSend && shov) {
                BfGlow.rect(ctx, sendX - 2, sendY - 2, sendW + 4, sendH + 4,
                        BfTheme.TEAL & 0xFFFFFF, 30, 5);
            }
            BfDraw.parallelogram(ctx, sendX, sendY, sendW, sendH, 4,
                    canSend ? BfTheme.TEAL : BfTheme.PANEL);
            BfDraw.border(ctx, sendX, sendY, sendW, sendH,
                    canSend ? BfTheme.TEAL : BfTheme.PANEL_LINE);
            int lbw = this.textRenderer.getWidth(lb);
            ctx.drawText(this.textRenderer, Text.literal(lb),
                    sendX + sendW / 2 - lbw / 2 + 3,
                    sendY + sendH / 2 - this.textRenderer.fontHeight / 2,
                    canSend ? 0xFF0A0D12 : BfTheme.MUTED, false);
        }

        // 主按钮
        boolean hover = inRect(mouseX, mouseY, submitX, submitY, submitW, submitH);
        if (hover && !busy) {
            BfGlow.rect(ctx, submitX - 3, submitY - 3, submitW + 6, submitH + 6,
                    BfTheme.TEAL & 0xFFFFFF, 60, 7);
        }
        BfDraw.parallelogram(ctx, submitX, submitY, submitW, submitH, 6,
                busy ? BfTheme.PANEL_LINE : BfTheme.TEAL);
        String act = busy ? "处理中…"
                : (registerMode ? "注 册" : (tokenMode ? "令牌登录" : "登 录"));
        int lw = this.textRenderer.getWidth(act);
        ctx.drawText(this.textRenderer, Text.literal(act),
                submitX + submitW / 2 - lw / 2 + 6,
                submitY + submitH / 2 - this.textRenderer.fontHeight / 2,
                busy ? BfTheme.MUTED : 0xFF0A0D12, false);

        // 切换 登录/注册
        String tg = registerMode ? "← 已有账号？返回登录"
                : (tokenMode ? "← 用账号密码登录" : "没有账号？邮箱验证码注册");
        int gw = this.textRenderer.getWidth(tg);
        boolean gh = inRect(mouseX, mouseY, toggleX, toggleY, toggleW, toggleH);
        ctx.drawText(this.textRenderer, Text.literal(tg),
                panelX + (panelW - gw) / 2, toggleY + 4,
                gh ? BfTheme.TEAL : BfTheme.MUTED, false);

        // 登录辅助行：浏览器登录 / 令牌登录（令牌模式下只剩浏览器登录，占满整行）
        if (!registerMode) {
            int dw = tokenMode ? deviceW * 2 + 8 : deviceW;
            boolean dh = inRect(mouseX, mouseY, deviceX, deviceY, dw, deviceH);
            if (dh && !devicePolling && !busy) {
                BfGlow.rect(ctx, deviceX - 2, deviceY - 2, dw + 4, deviceH + 4,
                        BfTheme.TEAL & 0xFFFFFF, 24, 4);
            }
            BfDraw.parallelogram(ctx, deviceX, deviceY, dw, deviceH, 4,
                    devicePolling ? BfTheme.PANEL_LINE : 0xE6161C25);
            BfDraw.border(ctx, deviceX, deviceY, dw, deviceH,
                    dh && !devicePolling ? BfTheme.TEAL : BfTheme.PANEL_LINE);
            String dl = devicePolling
                    ? (deviceCode.isEmpty() ? "正在请求设备码…" : "浏览器登录中… 设备码 " + deviceCode)
                    : (tokenMode ? "或 浏览器登录（PCL / FCL 等第三方启动器）" : "浏览器登录");
            int dlw = this.textRenderer.getWidth(dl);
            ctx.drawText(this.textRenderer, Text.literal(dl),
                    deviceX + dw / 2 - dlw / 2,
                    deviceY + deviceH / 2 - this.textRenderer.fontHeight / 2,
                    devicePolling ? BfTheme.TEAL : (dh ? BfTheme.TEXT : BfTheme.TEXT_DIM), false);

            if (!tokenMode) {
                boolean th = inRect(mouseX, mouseY, tokenX, tokenY, tokenW, tokenH);
                if (th && !busy) {
                    BfGlow.rect(ctx, tokenX - 2, tokenY - 2, tokenW + 4, tokenH + 4,
                            BfTheme.AMBER & 0xFFFFFF, 24, 4);
                }
                BfDraw.parallelogram(ctx, tokenX, tokenY, tokenW, tokenH, 4, 0xE6161C25);
                BfDraw.border(ctx, tokenX, tokenY, tokenW, tokenH,
                        th ? BfTheme.AMBER : BfTheme.PANEL_LINE);
                String tl = "令牌登录";
                int tlw = this.textRenderer.getWidth(tl);
                ctx.drawText(this.textRenderer, Text.literal(tl),
                        tokenX + tokenW / 2 - tlw / 2,
                        tokenY + tokenH / 2 - this.textRenderer.fontHeight / 2,
                        th ? BfTheme.TEXT : BfTheme.TEXT_DIM, false);
            }
        }

        // 反馈
        int fy = registerMode ? sendY + sendH + 12 : deviceY + deviceH + 10;
        if (!msgText.isEmpty()) {
            ctx.drawText(this.textRenderer, Text.literal(msgText),
                    panelX + 30, fy, msgOk ? BfTheme.GREEN : BfTheme.RED, false);
        }
    }

    private void renderSignedIn(DrawContext ctx, int mouseX, int mouseY) {
        panel(ctx);
        int ty = panelY + 24;
        ctx.drawText(this.textRenderer, Text.literal("已登录 Geekhonize"),
                panelX + 30, ty, BfTheme.GREEN, false);

        String u = GeoSession.username();
        int ux = panelX + 30;
        int uy = ty + 26;
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
                Text.literal("网页端找回/改密/换绑邮箱：auth.geekhonize.top"),
                ux, infoY + 14, BfTheme.FAINT, false);

        // 会话有效期（令牌临近过期会自动续期，无需手动操作）
        String validity;
        long exp = GeoSession.expiresAt();
        if (exp <= 0) {
            validity = "会话有效期：未知";
        } else {
            long left = exp - System.currentTimeMillis() / 1000;
            if (left <= 0) {
                validity = "会话已过期，请退出后重新登录";
            } else {
                long d = left / 86400, h = (left % 86400) / 3600, m = (left % 3600) / 60;
                String s = d > 0 ? (d + " 天 " + h + " 小时") : (h > 0 ? (h + " 小时 " + m + " 分") : (m + " 分"));
                validity = "会话剩余 " + s + "（自动续期）";
            }
        }
        ctx.drawText(this.textRenderer, Text.literal(validity),
                ux, infoY + 30, GeoSession.isExpired() ? BfTheme.RED : BfTheme.FAINT, false);

        boolean lh = inRect(mouseX, mouseY, logoutX, logoutY, logoutW, logoutH);
        if (lh) {
            BfGlow.rect(ctx, logoutX - 2, logoutY - 2, logoutW + 4, logoutH + 4,
                    BfTheme.RED & 0xFFFFFF, 34, 5);
        }
        BfDraw.parallelogram(ctx, logoutX, logoutY, logoutW, logoutH, 5, 0xE6161C25);
        BfDraw.border(ctx, logoutX, logoutY, logoutW, logoutH,
                lh ? BfTheme.RED_DIM : BfTheme.PANEL_LINE);
        String lb = "退出登录";
        int lbw = this.textRenderer.getWidth(lb);
        ctx.drawText(this.textRenderer, Text.literal(lb),
                logoutX + logoutW / 2 - lbw / 2,
                logoutY + logoutH / 2 - this.textRenderer.fontHeight / 2,
                lh ? BfTheme.RED : BfTheme.TEXT_DIM, false);

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
        BfDraw.fill(ctx, panelX + panelW - 6, panelY + panelH - 6, 6, 6, 0x2435E6D2);
    }

    private void drawBox(DrawContext ctx, String label, String val, int y,
                         boolean focused, int mx, int my, boolean secret, int maxLen) {
        ctx.drawText(this.textRenderer, Text.literal(label),
                boxX, y - 12, focused ? BfTheme.TEAL : BfTheme.MUTED, false);
        boolean hover = inRect(mx, my, boxX, y, boxW, boxH);
        BfDraw.border(ctx, boxX - (focused || hover ? 1 : 0), y - (focused || hover ? 1 : 0),
                boxW + (focused || hover ? 2 : 0), boxH + (focused || hover ? 2 : 0),
                focused ? BfTheme.TEAL : (hover ? BfTheme.PANEL_LINE : BfTheme.PANEL_LINE));
        BfDraw.fill(ctx, boxX, y, boxW, boxH, 0xF00B121C);

        String shown = secret ? mask(val) : val;
        if (secret && showPw) {
            shown = val;
        }
        int ty = y + boxH / 2 - this.textRenderer.fontHeight / 2;
        ctx.drawText(this.textRenderer, Text.literal(shown), boxX + 10, ty,
                BfTheme.TEXT, false);
        if (focused && blinkOn()) {
            int tw = this.textRenderer.getWidth(shown);
            BfDraw.fill(ctx, boxX + 12 + tw, ty, 1, this.textRenderer.fontHeight, BfTheme.TEAL);
        }
        if (secret && !val.isEmpty()) {
            String hint = showPw ? "隐藏" : "显示";
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
        // 输入行聚焦（注册模式 4 行，登录 2 行）
        for (int i = 0; i < rows; i++) {
            if (inRect(mx, my, boxX, boxY[i], boxW, boxH)) {
                focus = i;
                return true;
            }
        }
        // 验证码行较窄的框
        if (rows == 4) {
            int cdW = boxW - sendW - 10;
            if (inRect(mx, my, boxX, boxY[3], cdW, boxH)) {
                focus = 3;
                return true;
            }
            if (inRect(mx, my, sendX, sendY, sendW, sendH)) {
                doSendCode();
                return true;
            }
        }
        // 密码显示切换
        if (focus == 1 && !passBuf.isEmpty()
                && inRect(mx, my, boxX + boxW - 56, boxY[1], 46, boxH)) {
            showPw = !showPw;
            return true;
        }
        if (inRect(mx, my, submitX, submitY, submitW, submitH)) {
            submit();
            return true;
        }
        if (inRect(mx, my, toggleX, toggleY, toggleW, toggleH)) {
            msgText = "";
            focus = 0;
            if (tokenMode) {
                tokenMode = false;              // 令牌 → 账号密码
            } else {
                registerMode = !registerMode;    // 登录 ↔ 注册
            }
            return true;
        }
        if (!registerMode && inRect(mx, my, deviceX, deviceY,
                tokenMode ? deviceW * 2 + 8 : deviceW, deviceH)) {
            doDeviceLogin();
            return true;
        }
        // 令牌登录入口：切到令牌模式（面板只留一行令牌输入）
        if (!registerMode && !tokenMode
                && inRect(mx, my, tokenX, tokenY, tokenW, tokenH)) {
            tokenMode = true;
            focus = 0;
            msgOk = true;
            msgText = "在账号中心「我的账号 → 游戏令牌」生成后复制到这里";
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
            int n = rows;
            focus = (focus + 1) % n;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            if (tokenMode) {
                if (focus == 0 && !tokenBuf.isEmpty()) {
                    tokenBuf = tokenBuf.substring(0, tokenBuf.length() - 1);
                }
                return true;
            }
            if (focus == 0 && !userBuf.isEmpty()) {
                userBuf = userBuf.substring(0, userBuf.length() - 1);
            } else if (focus == 1 && !passBuf.isEmpty()) {
                passBuf = passBuf.substring(0, passBuf.length() - 1);
            } else if (focus == 2 && !emailBuf.isEmpty()) {
                emailBuf = emailBuf.substring(0, emailBuf.length() - 1);
            } else if (focus == 3 && !codeBuf.isEmpty()) {
                codeBuf = codeBuf.substring(0, codeBuf.length() - 1);
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
        if (tokenMode) {
            if (focus == 0 && tokenBuf.length() < 2048) {
                tokenBuf += chr;
                return true;
            }
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
        if (focus == 2 && emailBuf.length() < 160) {
            emailBuf += chr;
            return true;
        }
        if (focus == 3 && codeBuf.length() < 6 && Character.isDigit(chr)) {
            codeBuf += chr;
            return true;
        }
        return false;
    }

    /** 浏览器设备码登录：请求码 → 跳浏览器 → 轮询至授权完成。 */
    private void doDeviceLogin() {
        if (busy || devicePolling) {
            return;
        }
        devicePolling = true;
        deviceCode = "";
        msgText = "正在请求设备码…";
        msgOk = false;
        java.util.concurrent.CompletableFuture.supplyAsync(GeoHttp::deviceStart)
                .thenAccept(s -> this.client.execute(() -> {
                    if (!s.ok()) {
                        devicePolling = false;
                        msgText = s.msg().isEmpty() ? "请求设备码失败" : s.msg();
                        msgOk = false;
                        return;
                    }
                    deviceCode = s.code();
                    msgText = "请在打开的网页登录并输入设备码 " + s.code() + "（10 分钟内有效）";
                    msgOk = true;
                    openBrowser("https://auth.geekhonize.top/#device?code=" + s.code());
                    startDevicePoll(s.code());
                }));
    }

    private void startDevicePoll(String code) {
        Thread t = new Thread(() -> {
            for (int i = 0; i < 120 && devicePolling; i++) {
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException e) {
                    return;
                }
                GeoHttp.DevicePoll p = GeoHttp.devicePoll(code);
                if (p.ok() && "approved".equals(p.status())) {
                    this.client.execute(() -> {
                        devicePolling = false;
                        onAuthResult(new GeoHttp.Res(true, p.token(), p.username(), "ok"), false);
                    });
                    return;
                }
                if (!p.ok() && p.msg().contains("过期")) {
                    this.client.execute(() -> {
                        devicePolling = false;
                        msgText = "设备码已过期，请重新点击浏览器登录";
                        msgOk = false;
                    });
                    return;
                }
            }
            this.client.execute(() -> {
                if (devicePolling) {
                    devicePolling = false;
                    msgText = "等待授权超时，请重试";
                    msgOk = false;
                }
            });
        });
        t.setDaemon(true);
        t.start();
    }

    private static void openBrowser(String url) {
        try {
            net.minecraft.util.Util.getOperatingSystem().open(java.net.URI.create(url));
        } catch (Exception ignored) {
            // 无桌面环境/打开失败时，玩家仍可手动访问网页输入设备码
        }
    }

    private void doSendCode() {
        if (busy || sendBusy) {
            return;
        }
        String email = emailBuf.trim();
        if (!email.contains("@") || email.length() < 5) {
            msgText = "请先填写有效邮箱";
            msgOk = false;
            focus = 2;
            return;
        }
        sendBusy = true;
        java.util.concurrent.CompletableFuture.supplyAsync(() -> GeoHttp.sendCode(email, "register"))
                .thenAccept(r -> this.client.execute(() -> {
                    sendBusy = false;
                    if (r.ok()) {
                        sendCdUntil = System.currentTimeMillis() + 60_000;
                        msgText = "验证码已发送";
                        msgOk = true;
                        if (!r.devCode().isEmpty() && codeBuf.isEmpty()) {
                            codeBuf = r.devCode(); // stub 联调自动回填
                            focus = 3;
                        }
                    } else {
                        msgText = r.msg() == null || r.msg().isEmpty() ? "发送失败" : r.msg();
                        msgOk = false;
                    }
                }));
    }

    /** 令牌登录：校验粘贴的长期令牌，通过后直接落盘为本地会话。 */
    private void submitToken() {
        String tk = tokenBuf.trim();
        if (tk.isEmpty()) {
            msgText = "请粘贴账号中心生成的游戏令牌";
            msgOk = false;
            return;
        }
        busy = true;
        msgText = "";
        java.util.concurrent.CompletableFuture.supplyAsync(() -> GeoHttp.me(tk))
                .thenAccept(r -> this.client.execute(() -> onAuthResult(r, false)));
    }

    private void submit() {
        if (busy) {
            return;
        }
        if (tokenMode) {
            submitToken();
            return;
        }
        String u = userBuf.trim();
        String p = passBuf;
        if (u.isEmpty() || p.isEmpty()) {
            msgText = "请输入用户名与密码";
            msgOk = false;
            return;
        }
        if (registerMode) {
            String email = emailBuf.trim();
            String code = codeBuf.trim();
            if (email.isEmpty() || code.isEmpty()) {
                msgText = "注册需填写邮箱与验证码";
                msgOk = false;
                return;
            }
            final String fe = email;
            final String fc = code;
            busy = true;
            msgText = "";
            java.util.concurrent.CompletableFuture.supplyAsync(() -> GeoHttp.register(u, p, fe, fc))
                    .thenAccept(r -> this.client.execute(() -> onAuthResult(r, true)));
        } else {
            busy = true;
            msgText = "";
            java.util.concurrent.CompletableFuture.supplyAsync(() -> GeoHttp.login(u, p))
                    .thenAccept(r -> this.client.execute(() -> onAuthResult(r, false)));
        }
    }

    private void onAuthResult(GeoHttp.Res r, boolean reg) {
        busy = false;
        if (r.ok()) {
            GeoSession.save(r.token(), r.username());
            msgText = (reg ? "注册并登录成功：" : "登录成功：") + r.username();
            msgOk = true;
            if (this.client.getNetworkHandler() != null) {
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
                        .send(new com.breakfront.net.AuthLoginPayload(r.token()));
            }
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
            msgText = r.msg() == null || r.msg().isEmpty() ? "操作失败" : r.msg();
            msgOk = false;
        }
    }

    private void doLogout() {
        GeoSession.clear();
        if (this.client.getNetworkHandler() != null) {
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
                    .send(new com.breakfront.net.AuthLoginPayload(""));
        }
        userBuf = "";
        passBuf = "";
        emailBuf = "";
        codeBuf = "";
        registerMode = false;
        sendCdUntil = 0;
        devicePolling = false;
        deviceCode = "";
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
