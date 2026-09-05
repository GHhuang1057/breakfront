package com.breakfront.client.mixin;

import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 去除原版音效（用户点名）：拦截 SoundManager 入口，取消全部 minecraft: 命名空间音效。
 *
 * 保留白名单：
 * - WEATHER 分类（雷暴天气的氛围雨声/雷声——服务端默认雷暴天气，需保留气氛）
 * - tacz: / breakfront: / 其它第三方命名空间（枪声、命中反馈、自研 UI 音频不误伤）
 *
 * 被静音的原版声音（示例）：脚步/环境/方块破坏/UI 点击/受伤 oof/僵尸咕噜/背景音乐等。
 */
@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {

    private static boolean bfKeepSound(SoundInstance sound) {
        if (sound == null) {
            return true;
        }
        Identifier id = sound.getId();
        if (id == null) {
            return true;
        }
        String ns = id.getNamespace();
        if (!ns.equals("minecraft")) {
            return true; // 第三方（tacz 枪声等）一律放行
        }
        // 原版音效里仅保留雷雨氛围（服务器强制雷暴天气，用于战场氛围）
        try {
            return sound.getCategory() == SoundCategory.WEATHER;
        } catch (Exception e) {
            return false;
        }
    }

    /** play(SoundInstance) —— 同步播放入口。 */
    @Inject(method = "play(Lnet/minecraft/client/sound/SoundInstance;)V",
            at = @At("HEAD"), cancellable = true)
    private void breakfront_cancelVanillaSound(SoundInstance sound, CallbackInfo ci) {
        if (!bfKeepSound(sound)) {
            ci.cancel();
        }
    }

    /** play(SoundInstance, int) —— 带延迟的播放入口（部分系统音效）。 */
    @Inject(method = "play(Lnet/minecraft/client/sound/SoundInstance;I)V",
            at = @At("HEAD"), cancellable = true)
    private void breakfront_cancelVanillaSoundDelayed(SoundInstance sound, int delay, CallbackInfo ci) {
        if (!bfKeepSound(sound)) {
            ci.cancel();
        }
    }
}
