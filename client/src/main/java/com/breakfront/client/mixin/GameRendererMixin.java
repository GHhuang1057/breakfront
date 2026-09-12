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
 * <p>方法签名随版本略有差异，故在 mixins.json 标 {@code "required": false}：
 * 若当前版本方法签名不匹配，仅跳过、不崩溃（优雅降级——手部照常显示，不影响其它功能）。
 */
@Mixin(value = GameRenderer.class, required = false)
public abstract class GameRendererMixin {

    @Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
    private void bf$hideHandInDeploy(Camera camera, float tickDelta, float f, CallbackInfo ci) {
        if (BfDeployCamera.active() && BfDeployCamera.mode() == BfDeployCamera.Mode.OVERHEAD) {
            ci.cancel();
        }
    }
}
