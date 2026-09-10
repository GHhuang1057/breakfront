package com.breakfront.client.util;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

/**
 * AI 假玩家的客户端识别工具（单一事实来源）。
 *
 * <p>为什么靠**名字前缀**而不是命令标签/玩家列表：BOT 是服务端用
 * {@code BotPlayerFactory} 造的真实 {@code ServerPlayerEntity}，其 NBT（含命令标签
 * {@code breakfront.bot}）**不会同步给其它客户端** —— 客户端只能看到一个普通玩家。
 * 唯一稳定可见的判据就是玩家名，服务端在 {@code BotSquad#spawn} 里统一按
 * {@code BF_A<序号>} / {@code BF_D<序号>} 命名（A=攻方 ATTACKER、D=守方 DEFENDER）。
 *
 * <p>服务端若改命名规则，务必同步 {@link #PREFIX} 与 {@link #sideOfBotName}。
 */
public final class BotNames {

    /** BOT 名字前缀（对应服务端 BotSquad 的 NAME_PREFIX）。 */
    public static final String PREFIX = "BF_";

    private BotNames() {
    }

    /** 该实体是否为 AI 假玩家（按名字前缀判定）。 */
    public static boolean isBotName(Entity e) {
        return e instanceof PlayerEntity pe && isBotName(pe.getGameProfile().getName());
    }

    public static boolean isBotName(String name) {
        return name != null && name.startsWith(PREFIX);
    }

    /**
     * 从 BOT 名字解析阵营序号：0=攻 / 1=守 / -1=不是 BOT 或无法判定。
     */
    public static int sideOfBotName(String name) {
        if (name == null || name.length() < 4 || !name.startsWith(PREFIX)) {
            return -1;
        }
        char c = name.charAt(PREFIX.length());
        return c == 'A' ? 0 : c == 'D' ? 1 : -1;
    }
}
