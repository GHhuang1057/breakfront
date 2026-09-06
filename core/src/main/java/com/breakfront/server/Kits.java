package com.breakfront.server;

import com.breakfront.weapon.WeaponCatalog;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

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

    /** 兵种 → 默认主武器（与 WeaponCatalog.CLASS_GUNS 首把保持一致）。 */
    private static final Map<String, KitSpec> KITS = Map.of(
            "assault", new KitSpec("hk416d", "556x45", 30, 180),
            "engineer", new KitSpec("aa12", "12g", 8, 48),
            "support", new KitSpec("m249", "556x45", 75, 300),
            "recon", new KitSpec("kar98", "792x57", 4, 40));

    /** 单枪 → 发放规格（覆盖所有兵种白名单内的枪 + 若干额外枪）。 */
    private static final Map<String, KitSpec> GUNS = Map.of(
            "hk416d", new KitSpec("hk416d", "556x45", 30, 180),
            "m4a1", new KitSpec("m4a1", "556x45", 30, 180),
            "aa12", new KitSpec("aa12", "12g", 8, 48),
            "m590", new KitSpec("m590", "12g", 6, 36),
            "m249", new KitSpec("m249", "556x45", 75, 300),
            "kar98", new KitSpec("kar98", "792x57", 4, 40),
            "mk14", new KitSpec("mk14", "762x39", 20, 80),
            "ump45", new KitSpec("ump45", "45acp", 25, 150),
            "glock17", new KitSpec("glock17", "9mm", 17, 68));

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

    /**
     * 套件发放：按玩家实际「兵种 + 主武器」发放（无选定枪则用兵种默认枪）。
     * 主武器强制上主手（满弹匣），备弹进背包。
     * 2026-09-06：改用 Java API 直接置物（1.21.1 命令层 legacy {NBT} 物品语法已废弃，
     * replaceitem/give 带 GunId NBT 会解析失败 trailing data → 玩家/bot 空手根因）。
     */
    public static void giveKit(ServerMatch match, MinecraftServer server, ServerPlayerEntity player) {
        String classId = match.teams().classOf(player.getUuid());
        String chosen = match.teams().gunIdOf(player.getUuid());
        // 校验白名单：不在白名单（含未知 gunId）回退该兵种默认枪
        String gunId = WeaponCatalog.isGunAllowed(classId, chosen)
                ? chosen : WeaponCatalog.defaultGun(classId);
        KitSpec spec = GUNS.getOrDefault(gunId, GUNS.get(WeaponCatalog.defaultGun(classId)));
        equipGun(player, spec);
        giveAmmo(player, spec);
    }

    /**
     * 主手挂枪（LivingEntity：真人玩家与 NPC bot 通用）。
     * 运行时按注册表取 TaCZ 物品，NBT 经 DataComponent CUSTOM_DATA 写入 ——
     * 与 TaCZ 读取键（GunId / GunCurrentAmmoCount）一致，且无任何编译期依赖。
     */
    public static void equipGun(LivingEntity le, KitSpec spec) {
        if (le == null || spec == null) {
            return;
        }
        Item gun = Registries.ITEM.get(Identifier.tryParse("tacz:modern_kinetic_gun"));
        if (gun == null || gun == Items.AIR) {
            return; // TaCZ 未装载：静默（bot 走近战路径兜底）
        }
        ItemStack st = new ItemStack(gun);
        NbtCompound tg = new NbtCompound();
        tg.putString("GunId", "tacz:" + spec.gunId());
        tg.putInt("GunCurrentAmmoCount", spec.magSize());
        st.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tg));
        le.equipStack(EquipmentSlot.MAINHAND, st);
    }

    /** 备弹入背包（按物品堆叠上限拆分，背包满则丢弃不阻塞）。 */
    public static void giveAmmo(ServerPlayerEntity player, KitSpec spec) {
        if (player == null || spec == null || spec.spareAmmo() <= 0) {
            return;
        }
        Item ammo = Registries.ITEM.get(Identifier.tryParse("tacz:ammo"));
        if (ammo == null || ammo == Items.AIR) {
            return;
        }
        int left = spec.spareAmmo();
        int max = Math.max(1, Math.min(64, ammo.getMaxCount()));
        while (left > 0) {
            int n = Math.min(max, left);
            ItemStack a = new ItemStack(ammo, n);
            NbtCompound ac = new NbtCompound();
            ac.putString("AmmoId", "tacz:" + spec.ammoId());
            a.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(ac));
            player.getInventory().offerOrDrop(a);
            left -= n;
        }
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
