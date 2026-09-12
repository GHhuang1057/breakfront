package com.breakfront.client.mixin;

import com.breakfront.client.hud.BfDeployCamera;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 部署俯瞰期（OVERHEAD）隐藏第一人称手部与手持物。
 *
 * <p>部署屏打开时相机停在 3D 俯瞰，但原版仍会在屏幕底部渲染第一人称手臂/枪模，
 * 观感即「还没进第一人称就拿着枪」。此处取消手部渲染，直到滑落到第一人称。
 *
 * <p>目标方法签名锁定于 1.21.1 的 {@code GameRenderer.renderHand(Camera, float, float)}。
 * 本 Mixin 版本（Loom 1.17.20 内置）的 {@code @Mixin} 不支持 {@code required} 属性，
 * 且 mixins.json 的列表仅接受字符串，故该 mixin 为硬必需：跨 MC 版本时须复核 renderHand 签名，
 * 若方法被重命名/移除将导致客户端启动失败（而非优雅降级）。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
    private void bf$hideHandInDeploy(Camera camera, float tickDelta, float f, CallbackInfo ci) {
        if (BfDeployCamera.active() && BfDeployCamera.mode() == BfDeployCamera.Mode.OVERHEAD) {
            ci.cancel();
        }
    }
}
