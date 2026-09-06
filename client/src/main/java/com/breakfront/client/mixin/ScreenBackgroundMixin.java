package com.breakfront.client.mixin;

import com.breakfront.client.bf.BfDraw;
import com.breakfront.client.bf.BfTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 全 vanilla 屏「BF 画皮」：替换原版 Screen 的 dirt/深色背景为 BF 科幻蓝绿渐变底。
 *
 * <p>只对 <b>net.minecraft.client.gui.screen.*</b> 的原版屏生效（Options/Chat/Container/
 * 创造背包/成就/服务器列表等全被覆盖），我们自绘屏（com.breakfront…）与第三方模组屏
 * （org./com… 其它包）一律不干扰 —— 判断依据是运行时 Screen 具体类的包名前缀。
 *
 * <p>注入点：{@code Screen#renderBackground(DrawContext,int,int,float)} 的 HEAD 并 cancel，
 * 原版 dirt 平铺被整块替代；子类若 override 该函数并调用 super 也会走本注入。
 */
@Mixin(Screen.class)
public abstract class ScreenBackgroundMixin {

    private static boolean bf$vanilla(Screen self) {
        String cn = self.getClass().getName();
        return cn.startsWith("net.minecraft.client.gui.screen");
    }

    @Inject(method = "renderBackground",
            at = @At("HEAD"),
            cancellable = true)
    private void bf$bfBackdrop(DrawContext ctx, int mouseX, int mouseY, float delta,
                               CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        if (!bf$vanilla(self)) {
            return; // 非原版屏（第三方/BF 自绘）：保持其自身背景
        }
        ci.cancel();
        int sw = ctx.getScaledWindowWidth();
        int sh = ctx.getScaledWindowHeight();
        // 深空渐变底（上深下略暖）
        BfDraw.gradientV(ctx, 0, 0, sw, sh,
                BfTheme.BG_DEEP, BfTheme.BG_UP);
        // 四周暗角（半透明黑框内收）
        int vi = (int) (sh * 0.10);
        BfDraw.fill(ctx, 0, 0, sw, vi, 0x4405070A);
        BfDraw.fill(ctx, 0, sh - vi, sw, vi, 0x5005070A);
        // 顶部细 TEAL 光带 + 底部品牌条
        ctx.fill(0, 0, sw, 2, BfTheme.TEAL);
        ctx.fill(sw - 120, sh - 3, sw, sh, BfTheme.TEAL_DIM);
    }
}
