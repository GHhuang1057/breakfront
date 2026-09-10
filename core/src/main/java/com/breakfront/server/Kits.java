package com.breakfront.server;

import com.breakfront.weapon.WeaponCatalog;
import com.breakfront.weapon.WeaponSpec;
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

import java.util.LinkedHashMap;
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

    /**
     * 单枪 → 发放规格。**备弹量以 {@link WeaponCatalog} 的 {@code reserveAmmo} 为唯一真源**，
     * 按武器族区分（用户 2026-09-10 指定）：突击步枪 180 / 霰弹枪 80 / 狙击步枪与
     * 精确射手步枪 50；轻机枪 300、冲锋枪 150、手枪 68 维持原值。
     * 这里只写「弹匣容量 + 弹药 id」，备弹数从目录里查，避免两处硬编码对不上。
     */
    private static final Map<String, KitSpec> GUNS = buildGuns();

    private static Map<String, KitSpec> buildGuns() {
        Map<String, Integer> mags = Map.of(
                "hk416d", 30, "m4a1", 30,
                "aa12", 8, "m590", 6,
                "m249", 75, "kar98", 4,
                "mk14", 20, "ump45", 25, "glock17", 17);
        Map<String, String> ammos = Map.of(
                "hk416d", "556x45", "m4a1", "556x45",
                "aa12", "12g", "m590", "12g",
                "m249", "556x45", "kar98", "792x57",
                "mk14", "762x39", "ump45", "45acp", "glock17", "9mm");
        Map<String, KitSpec> m = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : mags.entrySet()) {
            String gun = e.getKey();
            int reserve = WeaponCatalog.byId(gun)
                    .map(WeaponSpec::reserveAmmo).orElse(120);
            m.put(gun, new KitSpec(gun, ammos.get(gun), e.getValue(), reserve));
        }
        return Map.copyOf(m);
    }

    /** 兵种 → 默认主武器（与 WeaponCatalog.CLASS_GUNS 首把保持一致）。 */
    private static final Map<String, KitSpec> KITS = Map.of(
            "assault", GUNS.get("hk416d"),
            "engineer", GUNS.get("aa12"),
            "support", GUNS.get("m249"),
            "recon", GUNS.get("kar98"));

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
        giveAmmo(server, player, spec);
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

    /**
     * 备弹入背包（走**指令文本**，由原版 /give 负责按每堆上限自动分堆）。
     *
     * <p>为什么不在 Java 里 new ItemStack：TaCZ 用 mixin 把
     * {@code ItemStack.getMaxStackSize()} 覆写成「按 stack 里的 AmmoId 查弹药 index 的
     * stack_size」（实测 556x45=60、12g=36）。Java 路径下无论
     * {@code new ItemStack(ammo, n)} 还是 {@code setCount(n)}，数量都会被夹成 1
     * （组件 max_stack_size 写进去了、count 仍是 1），结果 180 发变成 180 个单发堆、
     * 背包瞬间塞满、其余掉地上 —— 用户反馈「子弹不能叠加」。
     *
     * <p>1.21 的 {@code item[component=value]} 语法可用（旧式 {@code {NBT}} 已废弃），
     * 实测 {@code give <p> tacz:ammo[minecraft:custom_data={AmmoId:"tacz:556x45"}] 180}
     * 会正确给出 3 堆 ×60。故这里直接用指令发放，与「零 TaCZ 编译期依赖」的既定路线一致。
     */
    public static void giveAmmo(MinecraftServer server, ServerPlayerEntity player, KitSpec spec) {
        if (server == null || player == null || spec == null || spec.spareAmmo() <= 0) {
            return;
        }
        exec(server, String.format(
                "give %s tacz:ammo[minecraft:custom_data={AmmoId:\"tacz:%s\"}] %d",
                player.getGameProfile().getName(), spec.ammoId(), spec.spareAmmo()));
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
