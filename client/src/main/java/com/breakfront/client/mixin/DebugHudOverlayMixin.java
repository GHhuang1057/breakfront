package com.breakfront.client.mixin;

import com.breakfront.client.hud.BfDebugOverlay;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.DebugHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 接管原版 F3（DebugHud）—— 取消整块原版调试文本，改由 BfDebugOverlay 绘制 BF 精简层。
 *
 * <p>注入点选择：{@code DebugHud#render(DrawContext)} 的 HEAD 并 cancellable。
 * 理由（对比其它候选）：
 *  - getLeftText() / getRightText() 只在两处分别提供文本，需两处同时改写，且右侧的
 *    实体/区块/声音等统计与调试饼图仍由原版绘制，无法整体替换，叠绘风险高；
 *  - render 是 DebugHud 唯一对外绘制入口，HEAD 处 cancel 后，左/右文本、调试饼图
 *    全部不再由原版绘制，改动面最小、最稳，且天然避免与原版残留文本叠绘。
 * 因此采用「取消 + 自绘」而非「改文本列表」方案。
 */
@Mixin(DebugHud.class)
public abstract class DebugHudOverlayMixin {

    @Inject(method = "render",
            at = @At("HEAD"),
            cancellable = true)
    private void bf$renderDebugOverlay(DrawContext context, CallbackInfo ci) {
        ci.cancel();
        BfDebugOverlay.draw(context, MinecraftClient.getInstance());
    }
}
