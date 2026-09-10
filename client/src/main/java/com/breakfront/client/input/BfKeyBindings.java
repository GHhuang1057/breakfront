package com.breakfront.client.input;

import com.breakfront.client.BreakfrontClient;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BREAKFRONT 按键布局 —— 完全照 BF2042 官方 PC 默认键位重建，并清掉原版 Minecraft 的布局。
 *
 * <p>设计要点：
 * <ul>
 *   <li>① 注册一整套 BF2042 动作键位（换弹/近战/投掷物/标记/互动/卧倒/地图/呼叫菜单/附加菜单/
 *       语音/武器槽 1-4/切换座位），全部归入 {@code key.categories.breakfront} 类别，
 *       在「选项 → 控制」里可见、可重绑。</li>
 *   <li>② 首次进入游戏（或命令 {@code /bfkey apply}）把**原版**键位重排成 BF2042 布局：
 *       冲刺→Left Shift、蹲下→Left Ctrl、聊天→H，并把与 BF 动作冲突的原版绑定
 *       （丢弃 Q / 背包 E / 副手 F / 隐藏 HUD F1 / 进度 L）置为「未绑定」，
 *       腾出键位给 BF 动作用。这就是「去除原 MC 键盘布局」。</li>
 *   <li>③ 保留原版中与 BF 一致的键：WASD、空格跳跃、左键开火、右键瞄准、Tab 计分板。</li>
 * </ul>
 *
 * <p>BF2042 官方默认（EA 文档，2026-09-10 取证）：
 * 移动 WASD / 跳跃 空格 / 冲刺 Left Shift(长按) / 蹲下 Left Ctrl 或长按 C / 卧倒·滑铲 Z /
 * 开火 左键 / 瞄准 右键 / 换弹 R / 近战 F / 投掷物 G / 标记(Commo Rose) Q(长按) /
 * 进入·离开载具 E / 急救 E(长按) / 呼叫菜单 B(长按) / 附加菜单 T / 完整地图 M /
 * 计分板 Tab(长按) / 聊天 H / 语音 Left Alt / 主·副武器 1·2 / 专长 3 / 配备 4 /
 * 切换座位 F1 / 菜单 Esc。
 */
public final class BfKeyBindings {

    /** 控制类别（与语言文件 {@code key.categories.breakfront} 对应）。 */
    public static final String CATEGORY = "key.categories.breakfront";

    // ---- BF2042 动作键位（默认键即 BF 官方默认）----
    public static final KeyBinding RELOAD    = mk("reload",    GLFW.GLFW_KEY_R,        "换弹");
    public static final KeyBinding MELEE     = mk("melee",     GLFW.GLFW_KEY_F,        "近战");
    public static final KeyBinding GRENADE   = mk("grenade",   GLFW.GLFW_KEY_G,        "投掷物");
    public static final KeyBinding SPOT      = mk("spot",      GLFW.GLFW_KEY_Q,        "标记 / Ping");
    public static final KeyBinding INTERACT  = mk("interact",  GLFW.GLFW_KEY_E,        "进入·离开载具 / 互动");
    public static final KeyBinding PRONE     = mk("prone",     GLFW.GLFW_KEY_Z,        "卧倒 / 滑铲");
    public static final KeyBinding MAP       = mk("map",       GLFW.GLFW_KEY_M,        "完整地图");
    public static final KeyBinding CALL_MENU = mk("callmenu",  GLFW.GLFW_KEY_B,        "呼叫菜单");
    public static final KeyBinding PLUS_MENU = mk("plusmenu",  GLFW.GLFW_KEY_T,        "附加菜单");
    public static final KeyBinding VOIP      = mk("voip",      GLFW.GLFW_KEY_LEFT_ALT, "语音通话");
    public static final KeyBinding WEAPON_1  = mk("weapon1",   GLFW.GLFW_KEY_1,        "主武器");
    public static final KeyBinding WEAPON_2  = mk("weapon2",   GLFW.GLFW_KEY_2,        "副武器");
    public static final KeyBinding WEAPON_3  = mk("weapon3",   GLFW.GLFW_KEY_3,        "专长");
    public static final KeyBinding WEAPON_4  = mk("weapon4",   GLFW.GLFW_KEY_4,        "配备 / 道具");
    public static final KeyBinding SWAP_SEAT = mk("swapseat",  GLFW.GLFW_KEY_F1,       "切换座位（载具）");

    /** 全部 BF 动作键位，供遍历/重置用。 */
    private static final KeyBinding[] ALL = {
            RELOAD, MELEE, GRENADE, SPOT, INTERACT, PRONE, MAP, CALL_MENU,
            PLUS_MENU, VOIP, WEAPON_1, WEAPON_2, WEAPON_3, WEAPON_4, SWAP_SEAT
    };

    /** 原版键位 → BF2042 目标键（translation key 形式，如 {@code key.keyboard.left.shift}）。 */
    private static final Map<String, String> VANILLA_REMAP = new LinkedHashMap<>();
    static {
        VANILLA_REMAP.put("key.sprint",        "key.keyboard.left.shift");   // 原版默认 Left Control
        VANILLA_REMAP.put("key.sneak",         "key.keyboard.left.control"); // 原版默认 Left Shift
        VANILLA_REMAP.put("key.chat",          "key.keyboard.h");            // 原版默认 T
        // 以下原版绑定与 BF 动作键冲突 → 置为未绑定，腾出键位
        VANILLA_REMAP.put("key.drop",          "key.keyboard.unknown");      // 原版 Q → 让给标记
        VANILLA_REMAP.put("key.inventory",     "key.keyboard.unknown");      // 原版 E → 让给互动
        VANILLA_REMAP.put("key.swapHands",     "key.keyboard.unknown");      // 原版 F → 让给近战
        VANILLA_REMAP.put("key.hideHud",       "key.keyboard.unknown");      // 原版 F1 → 让给切座
        VANILLA_REMAP.put("key.advancements",  "key.keyboard.unknown");      // 原版 L → 让给自由键
    }

    private static KeyBinding mk(String id, int glfw, String zh) {
        // 翻译键同时写进资源文件；此处只建对象，描述由语言文件提供
        return new KeyBinding("breakfront.key." + id, InputUtil.Type.KEYSYM, glfw, CATEGORY);
    }

    private BfKeyBindings() {}

    /** 注册全部 BF 动作键位（在 {@code onInitializeClient} 调用一次）。 */
    public static void register() {
        for (KeyBinding kb : ALL) {
            KeyBindingHelper.registerKeyBinding(kb);
        }
        BreakfrontClient.LOGGER.info("[Breakfront] 已注册 {} 个 BF2042 动作键位", ALL.length);
    }

    /** 是否已按下（边沿，需在 client tick 内调用）。 */
    public static boolean pressed(KeyBinding kb) {
        return kb.wasPressed();
    }

    /** 首次进入游戏自动套用 BF2042 布局（幂等，只在第一次做）。 */
    public static void ensureAppliedOnce(MinecraftClient client) {
        Path flag = flagFile();
        try {
            if (Files.exists(flag)) {
                return;
            }
            applyBf2042Preset(client);
            Files.createDirectories(flag.getParent());
            Files.writeString(flag, "applied=" + java.time.LocalDate.now());
        } catch (Exception e) {
            BreakfrontClient.LOGGER.warn("[Breakfront] BF2042 按键预设应用失败：{}", e.getMessage());
        }
    }

    /** 把原版键位重排成 BF2042 布局。 */
    public static void applyBf2042Preset(MinecraftClient client) {
        if (client == null || client.options == null) {
            return;
        }
        int changed = 0;
        for (KeyBinding kb : client.options.allKeys) {
            String target = VANILLA_REMAP.get(kb.getTranslationKey());
            if (target == null) {
                continue;
            }
            InputUtil.Key newKey = "key.keyboard.unknown".equals(target)
                    ? InputUtil.UNKNOWN_KEY
                    : InputUtil.fromName(target);
            if (!kb.getBoundKey().equals(newKey)) {
                kb.setBoundKey(newKey);
                changed++;
            }
        }
        KeyBinding.updateKeysByCode();
        client.options.write();
        BreakfrontClient.LOGGER.info("[Breakfront] 已套用 BF2042 按键布局（重排 {} 个原版键）", changed);
    }

    /** 恢复原版 Minecraft 默认键位（保留已注册的 BF 动作键位）。 */
    public static void resetVanilla(MinecraftClient client) {
        if (client == null || client.options == null) {
            return;
        }
        // 把所有原版键位按代码反查默认：直接清空我们改过的，让游戏下次用其内置默认。
        // 简化做法：把被我们改成 unknown 的还原为常见默认，其余不动。
        for (KeyBinding kb : client.options.allKeys) {
            String key = kb.getTranslationKey();
            InputUtil.Key def = defaultFor(key);
            if (def != null && !kb.getBoundKey().equals(def)) {
                kb.setBoundKey(def);
            }
        }
        KeyBinding.updateKeysByCode();
        client.options.write();
        BreakfrontClient.LOGGER.info("[Breakfront] 已恢复原版 Minecraft 默认键位");
    }

    private static InputUtil.Key defaultFor(String translationKey) {
        return switch (translationKey) {
            case "key.drop"         -> InputUtil.fromName("key.keyboard.q");
            case "key.inventory"    -> InputUtil.fromName("key.keyboard.e");
            case "key.swapHands"    -> InputUtil.fromName("key.keyboard.f");
            case "key.hideHud"      -> InputUtil.fromName("key.keyboard.f1");
            case "key.advancements" -> InputUtil.fromName("key.keyboard.l");
            case "key.chat"         -> InputUtil.fromName("key.keyboard.t");
            case "key.sprint"       -> InputUtil.fromName("key.keyboard.left.control");
            case "key.sneak"        -> InputUtil.fromName("key.keyboard.left.shift");
            default                 -> null;
        };
    }

    private static Path flagFile() {
        // 与 BfServerConfig 同目录：config/breakfront/keybind_bf2042.txt
        Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("breakfront");
        return dir.resolve("keybind_bf2042.txt");
    }
}
