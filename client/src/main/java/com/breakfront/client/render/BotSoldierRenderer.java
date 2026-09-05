package com.breakfront.client.render;

import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.util.Identifier;

/**
 * AI 增援「史蒂夫士兵」渲染器（M9 皮肤阶段 / 用户点名修正）。
 *
 * 服务端 bot 实体仍是 ZombieEntity（breakfront.npc 标签、命令驱动位移/占点/可击杀），
 * 这里在客户端把僵尸实体的渲染整体替换为：
 *   - PlayerEntityModel：玩家模型（人体站姿；走路摆腿由原版实体同步自动驱动）
 *   - 默认史蒂夫皮肤（textures/entity/player/wide/steve.png）
 *   - HeldItemFeatureRenderer：把实体主手装备画在右手上——服务端 NpcSquad 已按兵种
 *     给每个 bot 挂上 TaCZ 主武器 → 看到的每个士兵手里都有一把对应兵种的枪
 *
 * 无新增协议、无幽灵状态，位移/转身/受击白闪/行走动画全部沿用原版实体管线。
 * 注意：本渲染器替换的是 ZOMBIE 实体类型的渲染；BREAKFRONT 服务器不会自然生成僵尸，
 * 战场上的僵尸实体只可能是 AI 增援（若未来引入其它僵尸玩法需再评估）。
 */
public class BotSoldierRenderer extends LivingEntityRenderer<ZombieEntity, PlayerEntityModel<ZombieEntity>> {

    /** 默认史蒂夫皮肤（宽模型）。 */
    private static final Identifier STEVE_SKIN =
            Identifier.ofVanilla("textures/entity/player/wide/steve.png");

    public BotSoldierRenderer(EntityRendererFactory.Context context) {
        super(context,
                new PlayerEntityModel<>(context.getPart(EntityModelLayers.PLAYER), false),
                0.5f);
        // 把主手/副手物品画到手上（枪械模型由 TaCZ 客户端物品模型提供）
        this.addFeature(new HeldItemFeatureRenderer<>(this, context.getHeldItemRenderer()));
    }

    @Override
    public Identifier getTexture(ZombieEntity entity) {
        return STEVE_SKIN;
    }
}
