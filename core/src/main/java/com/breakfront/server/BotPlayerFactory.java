package com.breakfront.server;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 战场 AI 的「真人实体壳」工厂（2026-09-10 v1）。
 *
 * <h2>为什么用 ServerPlayerEntity 而不是 ZombieEntity</h2>
 * v0.5–v0.7 的 bot 用 {@code ZombieEntity} 作壳，带来三类难以根治的问题：
 * <ol>
 *   <li><b>渲染不稳定（"忽隐忽现"）</b>：非玩家实体的客户端追踪范围、区块依赖、
 *       实体剔除规则都与玩家不同；僵尸还有日光自燃、怪物 despawn 等环境干扰。</li>
 *   <li><b>行为要打补丁</b>：需手动禁用原版 AI、清目标、强制防火、压制音效与掉落。</li>
 *   <li><b>与玩法割裂</b>：TaCZ 弹道/命中判定、队伍染色、计分板、Tab 列表都无法复用
 *       真人通路，只能靠「模拟伤害」近似，导致击杀归属与伤害模型和真人两套逻辑。</li>
 * </ol>
 * 改用真实玩家实体后，bot 走与真人完全相同的实体、渲染、追踪、伤害与计分通路，
 * 「忽隐忽现」从根上消失，且 TaCZ 枪械可走真实弹道。
 *
 * <h2>实现要点（社区验证的「五步法」）</h2>
 * <ol>
 *   <li>构造离线 {@link GameProfile}（UUID 由 {@code OfflinePlayer:<name>} 派生，
 *       与离线模式真人一致，避免与服务端鉴权冲突）</li>
 *   <li>{@link ConnectedClientData#createDefault(GameProfile)} 生成客户端能力数据</li>
 *   <li>{@code new ServerPlayerEntity(server, world, profile, syncedOptions)}</li>
 *   <li>用 Netty {@link EmbeddedChannel} 背书的 {@link ClientConnection} 作为假连线 ——
 *       它吸收所有 S2C 包，使 {@code connection.send(...)} 这类通路不会 NPE，
 *       而无需真实网络对端</li>
 *   <li>{@code playerManager.onPlayerConnect(...)} 正式入列：注册到玩家列表、
 *       建立网络处理器、加入世界</li>
 * </ol>
 *
 * <p><b>注意</b>：假玩家没有真实客户端，因此不能依赖「客户端回传」的逻辑
 * （如计分板的统计同步、Tab 列表刷新）。移动/攻击一律由服务端直接驱动
 * （见 {@link #moveTo} / {@link #attack}）。
 */
public final class BotPlayerFactory {

    /** bot 名字前缀：便于日志辨识与指令选择器批量操作。 */
    public static final String NAME_PREFIX = "BF_";

    /**
     * bot 命令标签：全服唯一的「这是 AI 假玩家」判据。
     * 用标签而非玩家列表登记表，好处是重启/热更后对已存在实体依然有效，
     * 且任何模块（统计真人、友伤判定、管理台）都能无依赖地复用。
     */
    public static final String BOT_TAG = "breakfront.bot";

    private BotPlayerFactory() {
    }

    /** 实体是否为 BREAKFRONT 假玩家。 */
    public static boolean isBot(net.minecraft.entity.Entity e) {
        return e != null && e.getCommandTags().contains(BOT_TAG);
    }

    /**
     * 创建一个已入列的战场 bot。
     *
     * @param server 服务端实例
     * @param world  目标世界（通常主世界）
     * @param name   bot 名（需全局唯一；建议形如 {@code BF_Att_A1}）
     * @param x,y,z  初始坐标
     * @return 已加入玩家列表的 {@link ServerPlayerEntity}；失败返回 null
     */
    public static ServerPlayerEntity create(MinecraftServer server, ServerWorld world,
                                            String name, double x, double y, double z) {
        try {
            GameProfile profile = new GameProfile(offlineUuid(name), name);
            // 离线 UUID 与名字绑定，保证同名 bot 重启后身份稳定（计分板/回合记录可延续）
            // 第二参数 transferred：是否为「跨服转移」会话（1.21.1 起 createDefault 需要），
            // 假玩家非转移 → false。签名核对自 yarn 1.21.1 API 文档。
            ConnectedClientData clientData = ConnectedClientData.createDefault(profile, false);
            ServerPlayerEntity player = new ServerPlayerEntity(
                    server, world, profile, clientData.syncedOptions());

            // 假连线：EmbeddedChannel 吸收所有出站包，避免 send(...) NPE
            ClientConnection connection = new ClientConnection(NetworkSide.SERVERBOUND);
            new EmbeddedChannel(connection);

            server.getPlayerManager().onPlayerConnect(connection, player, clientData);

            // 入列后定位（onPlayerConnect 会把玩家放到世界出生点，需再挪到目标位置）
            player.teleport(world, x, y, z, 0.0f, 0.0f);
            player.setCustomName(Text.literal(name));
            player.setCustomNameVisible(false);
            // 打上 bot 标签（工厂负责，保证任何创建路径都带标记；阵营标签由调用方补）
            player.addCommandTag(BOT_TAG);
            return player;
        } catch (Throwable t) {
            BreakfrontServer.LOGGER.error("[BF-Bot] 创建假玩家 {} 失败: {}", name, t.toString());
            return null;
        }
    }

    /**
     * 移除 bot：先从玩家列表摘除（走标准断线通路，清理实体与追踪），再断开假连线。
     * 直接 {@code discard()} 会留下玩家列表条目，造成「幽灵在线」。
     */
    public static void remove(MinecraftServer server, ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        try {
            server.getPlayerManager().remove(bot);
        } catch (Throwable t) {
            BreakfrontServer.LOGGER.warn("[BF-Bot] 移除假玩家 {} 异常: {}",
                    bot.getGameProfile().getName(), t.toString());
        }
    }

    /** 服务端驱动的位移（假玩家无客户端输入，必须由服务端设置位置）。 */
    public static void moveTo(ServerPlayerEntity bot, double x, double y, double z, float yaw) {
        if (bot == null) {
            return;
        }
        bot.teleport(bot.getServerWorld(), x, y, z, yaw, 0.0f);
    }

    /** 服务端驱动的朝向（不改变位置）。 */
    public static void faceTo(ServerPlayerEntity bot, double tx, double tz) {
        if (bot == null) {
            return;
        }
        double dx = tx - bot.getX();
        double dz = tz - bot.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(dx, dz));
        bot.setYaw(yaw);
        bot.setHeadYaw(yaw);
        bot.setBodyYaw(yaw);
    }

    /** 离线模式 UUID：与 {@code OfflinePlayer:<name>} 算法一致，保证同名稳定。 */
    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
