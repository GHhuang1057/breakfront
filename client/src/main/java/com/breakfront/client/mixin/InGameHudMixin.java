package com.breakfront.client.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 去原生 HUD（M5）：
 * 取消 血量/饥饿/护甲/氧气、经验条与等级、物品栏、准星、手持物品名提示、
 * 坐骑血条、Buff 角标、原生 TAB 玩家列表 的原版绘制。
 *
 * 保留：聊天气泡、标题/副标题、着火/传送门等全屏遮罩（战斗必要反馈）。
 * 自定义 BF HUD 由 HudRenderCallback 在原生 render 末尾阶段另行绘制，
 * 此处只负责把原版元素"关掉"，避免与自绘 HUD 叠绘。
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

    @Inject(method = "renderStatusBars", at = @At("HEAD"), cancellable = true)
    private void bf$hideStatusBars(DrawContext context, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void bf$hideHotbar(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "renderExperienceBar", at = @At("HEAD"), cancellable = true)
    private void bf$hideXpBar(DrawContext context, int x, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "renderExperienceLevel", at = @At("HEAD"), cancellable = true)
    private void bf$hideXpLevel(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void bf$hideCrosshair(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "renderHeldItemTooltip", at = @At("HEAD"), cancellable = true)
    private void bf$hideHeldItemTooltip(DrawContext context, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "renderMountHealth", at = @At("HEAD"), cancellable = true)
    private void bf$hideMountHealth(DrawContext context, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "renderStatusEffectOverlay", at = @At("HEAD"), cancellable = true)
    private void bf$hideStatusEffects(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "renderPlayerList", at = @At("HEAD"), cancellable = true)
    private void bf$hideVanillaTabList(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        ci.cancel();
    }
}
