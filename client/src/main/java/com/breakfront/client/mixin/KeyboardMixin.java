package com.breakfront.client.mixin;

import com.breakfront.client.hud.BfKeymap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Keyboard;
import net.minecraft.client.gui.screen.ChatScreen;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * BREAKFRONT 键位接管（用户确认方案）：
 *  - 战斗阶段（COUNTDOWN/BATTLE）白名单制拦截原版按键（BfKeymap.shouldBlock）；
 *  - 新键位：Y = 聊天（替换被屏蔽的原版 T）；B = 阵亡时打开部署屏；
 *  - R 装填走 TaCZ 自带键位（白名单放行）；Tab 计分板 / 移动键全放行；
 *  - Screen 打开时不拦截（Esc 关部署屏等由 Screen 自理）；
 *  - RELEASE 永不拦截（防白名单键位卡住）。
 */
@Mixin(Keyboard.class)
public abstract class KeyboardMixin {

    @Shadow @Final private MinecraftClient client;

    @Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
    private void bf$keymap(long window, int key, int scancode, int action, int modifiers, CallbackInfo ci) {
        if (key == GLFW.GLFW_KEY_UNKNOWN) {
            return;
        }
        if (BfKeymap.shouldBlock(key, action, client)) {
            ci.cancel();
            return;
        }
        if (action != GLFW.GLFW_PRESS || client.currentScreen != null || !BfKeymap.battle()) {
            return;
        }
        if (client.player == null) {
            return;
        }
        if (key == GLFW.GLFW_KEY_Y) {
            client.setScreen(new ChatScreen(""));        // 全体聊天（原版 T 已被屏蔽）
            ci.cancel();
        } else if (key == GLFW.GLFW_KEY_B && !client.player.isAlive()) {
            client.setScreen(new com.breakfront.client.ui.BfDeployScreen(true));
            ci.cancel();
        }
    }
}
