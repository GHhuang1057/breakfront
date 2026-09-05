package com.breakfront.client.ui.admin;

import com.breakfront.client.bf.BfDraw;
import com.breakfront.client.bf.BfTheme;
import com.breakfront.client.state.AdminState;
import com.breakfront.net.AdminLoginPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * M8 管理员门禁屏：Ctrl+Shift+F8 呼出，输入密码后经 C2S 校验。
 * 密码错误时在屏上显示服务端返回的原因；成功后由客户端 tick 切到管理面板。
 * 不暂停游戏（对局中可随时呼出）。
 */
public class BfAdminGateScreen extends Screen {

    private TextFieldWidget passwordField;
    private int loginBtnX, loginBtnY, loginBtnW = 120, loginBtnH = 26;

    public BfAdminGateScreen() {
        super(Text.literal("ADMIN ACCESS"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;
        int fw = 220;
        passwordField = new TextFieldWidget(this.textRenderer,
                cx - fw / 2, cy - 6, fw, 20, Text.literal("管理员密码"));
        passwordField.setMaxLength(64);
        passwordField.setDrawsBackground(false);
        passwordField.setFocused(true);
        loginBtnX = cx + fw / 2 - loginBtnW - 8;
        loginBtnY = cy - 26;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int sw = this.width;
        int sh = this.height;
        int cx = sw / 2;
        int cy = sh / 2;
        // 背景近不透明——光影/动态世界透过半透明层会造成「糊」与不可读（用户实测反馈）
        BfDraw.gradientV(ctx, 0, 0, sw, sh, 0xF605070A, 0xFC0A0D12);
        BfDraw.fill(ctx, 0, 0, 4, sh, BfTheme.YELLOW);

        int pw = 460;
        int ph = 150;
        int px = cx - pw / 2;
        int py = cy - ph / 2 - 20;
        BfDraw.fill(ctx, px, py, pw, ph, BfTheme.PANEL);
        BfDraw.border(ctx, px, py, pw, ph, BfTheme.PANEL_LINE);

        ctx.drawText(this.textRenderer, Text.literal("ADMIN ACCESS  管理员入口"),
                px + 24, py + 18, BfTheme.YELLOW, false);
        ctx.drawText(this.textRenderer,
                Text.literal("Ctrl+Shift+F8 呼出 · 会话不提升原版权限（/bfs 编辑器级）"),
                px + 24, py + 34, BfTheme.MUTED, false);

        // 密码输入行（自绘底，透明文本框仅显示文字与光标）
        int fw = pw - 48 - 140;
        int fy = py + 62;
        int fx = px + 24 + 44;
        BfDraw.fill(ctx, px + 24, fy - 4, pw - 48, 28, 0xFF0A0D12);
        BfDraw.border(ctx, px + 24, fy - 4, pw - 48, 28, BfTheme.PANEL_LINE);
        ctx.drawText(this.textRenderer, Text.literal("密码"),
                px + 24, fy, BfTheme.TEXT_DIM, false);
        if (passwordField != null) {
            passwordField.setX(fx);
            passwordField.setY(fy + 1);
            passwordField.setWidth(fw);
            passwordField.setHeight(16);
            passwordField.render(ctx, mouseX, mouseY, delta);
        }

        // 登录按钮
        loginBtnX = px + pw - 24 - 120;
        loginBtnY = fy - 4;
        boolean hover = mouseX >= loginBtnX && mouseX <= loginBtnX + loginBtnW
                && mouseY >= loginBtnY && mouseY <= loginBtnY + loginBtnH;
        BfDraw.fill(ctx, loginBtnX, loginBtnY, loginBtnW, loginBtnH,
                hover ? BfTheme.ORANGE : 0xFF9A3E1D);
        String lbl = "登录 LOGIN";
        int lw = this.textRenderer.getWidth(lbl);
        ctx.drawText(this.textRenderer, Text.literal(lbl),
                loginBtnX + (loginBtnW - lw) / 2, loginBtnY + 7,
                hover ? 0xFFFFFFFF : 0xFFE8E4DC, false);

        // 状态行（服务端结果 / 本地提示）
        String msg = AdminState.lastMessage();
        if (msg != null && !msg.isEmpty()) {
            boolean err = !AdminState.isAdmin();
            ctx.drawText(this.textRenderer, Text.literal(msg),
                    px + 24, py + ph - 26,
                    err ? BfTheme.RED : BfTheme.GREEN, false);
        } else {
            ctx.drawText(this.textRenderer, Text.literal("提示：默认密码见服务端 breakfront-server.properties"),
                    px + 24, py + ph - 26, BfTheme.FAINT, false);
        }
        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && inLogin(mouseX, mouseY)) {
            tryLogin();
            return true;
        }
        if (passwordField != null) {
            passwordField.mouseClicked(mouseX, mouseY, button);
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            tryLogin();
            return true;
        }
        // 再按 Ctrl+Shift+F8 → 取消关闭
        if (keyCode == GLFW.GLFW_KEY_F8
                && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0
                && (modifiers & GLFW.GLFW_MOD_SHIFT) != 0) {
            if (this.client != null) {
                this.client.setScreen(null);
            }
            return true;
        }
        if (passwordField != null && passwordField.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (passwordField != null && passwordField.charTyped(chr, modifiers)) {
            return true;
        }
        return super.charTyped(chr, modifiers);
    }

    private boolean inLogin(double mx, double my) {
        return mx >= loginBtnX && mx <= loginBtnX + loginBtnW
                && my >= loginBtnY && my <= loginBtnY + loginBtnH;
    }

    private void tryLogin() {
        if (passwordField == null || this.client == null) {
            return;
        }
        if (this.client.getNetworkHandler() == null) {
            AdminState.applyResult(false, "未连接到服务器");
            return;
        }
        String pwd = passwordField.getText();
        if (pwd.isEmpty()) {
            AdminState.applyResult(false, "请输入密码");
            return;
        }
        AdminState.applyResult(false, "正在校验…");
        ClientPlayNetworking.send(new AdminLoginPayload(pwd));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
}
