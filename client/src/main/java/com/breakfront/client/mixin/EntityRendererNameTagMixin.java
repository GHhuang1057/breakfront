package com.breakfront.client.mixin;

import com.breakfront.client.util.BotNames;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 隐藏 AI 假玩家的原版名字牌。
 *
 * <p>BOT 已从僵尸壳换成**真实玩家壳**（{@code ServerPlayerEntity}，见 BotSquad /
 * BotPlayerFactory），于是原版会把 {@code BF_A1} 这类名字牌画在士兵头顶 —— 战场画面上
 * 一排「BF_A1 / BF_D3」非常出戏，也不符 BF 的观感（BF 里只有头顶菱形标记，没有名字牌）。
 *
 * <p>注入点选 {@code EntityRenderer#hasLabel(Entity)} 而非 {@code renderLabelIfPresent}：
 * 前者是原版「这个实体到底要不要画名字牌」的唯一判定入口（javadoc 原文：
 * "Determines whether the passed entity should render with a nameplate above its head"），
 * 在 HEAD 处返回 false 即可，连后续的矩阵/字体布局都省掉；后者仅是绘制动作，
 * 拦它属于「画到一半再丢弃」，既浪费又容易被其它渲染路径绕过。
 *
 * <p>判据复用 {@link BotNames#isBotName}（{@code BF_} 前缀），与客户端标记层、
 * 服务端命名保持同一口径。真人玩家不受影响。
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererNameTagMixin {

    @Inject(method = "hasLabel", at = @At("HEAD"), cancellable = true)
    private void bf$hideBotNameTag(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (BotNames.isBotName(entity)) {
            cir.setReturnValue(false);
        }
    }
}
