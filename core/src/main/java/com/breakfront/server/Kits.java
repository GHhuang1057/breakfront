package com.breakfront.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;

/**
 * 兵种战斗套件（M2 / 批 C：TaCZ 枪械发放）。
 *
 * TaCZ 枪械/弹药均为数据驱动物品（无 per-gun 注册 id）：
 *   枪：tacz:modern_kinetic_gun + NBT {GunId:"tacz:ak47", GunCurrentAmmoCount:30}
 *   弹：tacz:ammo            + NBT {AmmoId:"tacz:762x39"}
 * 因此发放只需给实体下发指令文本，不依赖 TaCZ 任何编译期类型；
 * TaCZ 缺席时 replaceitem/give 会失败但不会崩服（命令找不到物品仅刷一条日志）。
 *
 * 数值与 scripts/tune_tacz_pack.py 的 100HP 口径联动：
 * 弹药 id/弹匣容量取自默认枪包 data/tacz/data/guns/*_data.json 的 ammo/ammo_amount。
 */
public final class Kits {

    /** 兵种套件：主武器枪 id（无 tacz: 前缀）+ 弹药 id + 弹匣容量 + 额外备弹数。 */
    public record KitSpec(String gunId, String ammoId, int magSize, int spareAmmo) {
    }

    private static final Map<String, KitSpec> KITS = Map.of(
            "assault", new KitSpec("hk416d", "556x45", 30, 180),
            "engineer", new KitSpec("aa12", "12g", 8, 48),
            "support", new KitSpec("m249", "556x45", 75, 300),
            "recon", new KitSpec("kar98", "792x57", 4, 40));

    /** 兵种轮转顺序（bot 与真人共用同一套）。 */
    public static final String[] CLASSES = {"assault", "engineer", "support", "recon"};

    private Kits() {
    }

    /** 按兵种 id 查套件（未知兵种回退突击兵）。 */
    public static KitSpec spec(String classId) {
        return KITS.getOrDefault(classId, KITS.get("assault"));
    }

    /** 兵种主武器枪 id（无 tacz: 前缀，供 bot 挂装备/同步展示）。 */
    public static String gunIdOf(String classId) {
        return spec(classId).gunId();
    }

    /** 套件发放：主武器强制上主手（满弹匣），备弹进背包（指令自动按堆叠上限拆分）。 */
    public static void giveKit(ServerMatch match, MinecraftServer server, ServerPlayerEntity player) {
        KitSpec spec = KITS.getOrDefault(match.teams().classOf(player.getUuid()),
                KITS.get("assault"));
        String name = player.getGameProfile().getName();

        // 主手 = 主武器（满弹匣）——replaceitem 无条件覆盖，保证每次发放可预期
        String gunNbt = String.format("{GunId:\"tacz:%s\",GunCurrentAmmoCount:%d}",
                spec.gunId(), spec.magSize());
        exec(server, String.format("replaceitem entity %s weapon.mainhand "
                + "tacz:modern_kinetic_gun%s 1", name, gunNbt));
        // 备弹（弹药 id 不带 tacz: 前缀时同样补全；give 超过堆叠上限会自动拆组）
        exec(server, String.format("give %s tacz:ammo{AmmoId:\"tacz:%s\"} %d",
                name, spec.ammoId(), spec.spareAmmo()));
    }

    /** 服务端指令执行（与 ServerMatch.exec 同实现，避免跨类私有访问）。 */
    private static void exec(MinecraftServer server, String command) {
        try {
            server.getCommandManager().executeWithPrefix(server.getCommandSource(), command);
        } catch (Throwable t) {
            BreakfrontServer.LOGGER.warn("[Breakfront] kit exec failed '{}': {}",
                    command, t.toString());
        }
    }
}
