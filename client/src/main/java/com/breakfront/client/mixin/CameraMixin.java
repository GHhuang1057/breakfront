package com.breakfront.client.mixin;

import com.breakfront.client.hud.BfDeployCamera;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockView;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 部署阶段相机接管：BfDeployCamera 处于 OVERHEAD/GLIDE 时，
 * 在 Camera.update 末尾覆写位姿（俯瞰 → 平滑滑落到第一人称）。
 * 其余阶段零干预。
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow public abstract void setPos(Vec3d pos);

    @Shadow public abstract void setRotation(float yaw, float pitch);

    @Inject(method = "update", at = @At("TAIL"))
    private void bf$deployCamera(BlockView area, Entity focusedEntity, boolean thirdPerson,
                                 boolean inverseView, float tickDelta, CallbackInfo ci) {
        if (!BfDeployCamera.active()) {
            return;
        }
        float[] rot = new float[2];
        double[] p = BfDeployCamera.cameraPose(rot, focusedEntity);
        if (p == null) {
            return;
        }
        this.setPos(new Vec3d(p[0], p[1], p[2]));
        this.setRotation(rot[0], rot[1]);
    }
}
