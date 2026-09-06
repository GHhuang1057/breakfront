package com.breakfront.server;

import com.breakfront.game.Side;
import com.breakfront.net.KillFeedPayload;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务端组件装配器：把对局运行时挂到 Fabric 服务端生命周期。
 * 单例注册（onInitialize 调用一次，dedicated/integrated server 均生效）。
 */
public final class BreakfrontServer {

    public static final Logger LOGGER = LoggerFactory.getLogger("breakfront.server");

    private static ServerMatch match;
    private static ModUpdateServer updateServer;
    private static boolean registered = false;
    private static MinecraftServer currentServer;

    private BreakfrontServer() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            currentServer = server;
            match = new ServerMatch(server);
            KillListener.bind(match);
            updateServer = ModUpdateServer.start(server.getRunDirectory());
            LOGGER.info("[Breakfront] server match ready ({} zones, {} sectors)",
                    match.game().zoneCount(), match.game().sectors().size());
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            currentServer = null;
            match = null;
            if (updateServer != null) {
                updateServer.stop();
                updateServer = null;
            }
        });

        // S1：进服自动补位/战局部署、退服清理阵营
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (match != null) {
                match.onPlayerJoin(server, handler.getPlayer());
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (match != null) {
                match.onPlayerLeft(handler.getPlayer().getUuid());
            }
        });

        // S2（M2）：玩家重生（死亡后复活）→ 重发兵种装备（死亡默认清包）
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (match != null) {
                match.clearDeployChoice(newPlayer.getUuid());
                MinecraftServer srv = newPlayer.getServer();
                if (srv != null) {
                    match.kitPlayer(srv, newPlayer);
                }
            }
        });

        // W4a：兵种选择（C2S）——服务端记录，供 P3 装备发放
        ServerPlayNetworking.registerGlobalReceiver(com.breakfront.net.SetClassPayload.ID,
                (payload, context) -> context.player().server.execute(() -> {
                    ServerPlayerEntity p = context.player();
                    if (match == null || p == null) {
                        return;
                    }
                    String before = match.teams().classOf(p.getUuid());
                    match.teams().setClass(p.getUuid(), payload.classId());
                    String after = match.teams().classOf(p.getUuid());
                    if (!before.equals(after)) {
                        LOGGER.info("[Breakfront] {} set class -> {}", p.getName().getString(), after);
                    }
                }));

        // GeoAuth：玩家提交 Geekhonize 令牌绑定账号（防离线服自报名冒名）。
        // HTTP 校验放 IO 线程池（不阻塞 netty/主线程），结果回主线程执行绑定并回执。
        ServerPlayNetworking.registerGlobalReceiver(com.breakfront.net.AuthLoginPayload.ID,
                (payload, context) -> {
                    ServerPlayerEntity p = context.player();
                    String tok = payload.token() == null ? "" : payload.token().trim();
                    if (p == null || tok.isEmpty()) {
                        // 空令牌 = 解除本地绑定
                        context.player().server.execute(() -> {
                            var m = match();
                            if (m != null) {
                                m.unbindGeo(context.player().getUuid());
                            }
                            sendAuthResult(context.player(), 1, true, "", "", "已注销本地账号绑定");
                        });
                        return;
                    }
                    java.util.concurrent.CompletableFuture
                            .supplyAsync(() -> AuthBridge.me(tok))
                            .thenAccept(res -> p.server.execute(() -> {
                                var m = match();
                                if (m != null && res.ok()) {
                                    m.bindGeo(p.getUuid(), res.username(), res.roles());
                                }
                                sendAuthResult(p, 0, res.ok(), res.username(),
                                        String.join(",", res.roles()),
                                        res.ok() ? "已绑定 Geekhonize 账号"
                                                : "令牌校验失败：" + res.msg());
                            }));
                });

        // M8：管理员登录（C2S）——校验通过建立会话并回发结果；不提升 op
        ServerPlayNetworking.registerGlobalReceiver(com.breakfront.net.AdminLoginPayload.ID,
                (payload, context) -> context.player().server.execute(() -> {
                    ServerPlayerEntity p = context.player();
                    if (p == null || p.networkHandler == null) {
                        return;
                    }
                    boolean ok = AdminService.login(p.getUuid(), payload.password());
                    ServerPlayNetworking.send(p, new com.breakfront.net.AdminLoginResultPayload(
                            ok, ok ? "管理员会话已建立（" + AdminService.timeoutSecs + " 秒有效）"
                                    : "密码错误，请重试"));
                    if (ok) {
                        LOGGER.info("[Breakfront] admin session granted: {}", p.getName().getString());
                    } else {
                        LOGGER.warn("[Breakfront] admin login failed (bad password): {}",
                                p.getName().getString());
                    }
                }));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (match != null) {
                match.tick(server);
            }
        });

        // 击杀归属桥 · 第 1 层：vanilla 死亡事件（覆盖全部死因，负责扣票）
        ServerLivingEntityEvents.AFTER_DEATH.register(KillListener::onEntityDeath);

        // 友伤豁免：同阵营（真人/真人、真人/AI、AI/AI）之间伤害全部拦截
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (match == null) {
                return true;
            }
            var attacker = source.getAttacker() instanceof LivingEntity le ? le : null;
            if (attacker == null || attacker == entity) {
                return true; // 环境伤害/自伤放行
            }
            int as = sideOfEntity(attacker);
            int vs = sideOfEntity(entity);
            if (as >= 0 && as == vs) {
                return false; // 同阵营：豁免
            }
            return true;
        });

        // W5：命中反馈（白 X）——本玩家造成的非致死伤害
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (taken <= 0f || blocked) {
                return;
            }
            if (!entity.isAlive()) {
                return; // 致死伤：击杀反馈由死亡事件发红 X
            }
            if (source.getAttacker() instanceof ServerPlayerEntity shooter && shooter != entity) {
                sendHitMarker(shooter, 0);
            }
        });

        // 击杀归属桥 · 第 2 层：TaCZ 枪械击杀适配（模组缺席时自动跳过，不崩服）
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("tacz")) {
            try {
                TaCzKillAdapter.register();
                LOGGER.info("[Breakfront] TaCZ kill adapter attached");
            } catch (Throwable t) {
                LOGGER.warn("[Breakfront] TaCZ kill adapter failed to attach, degrade to vanilla tier: {}", t.toString());
            }
        } else {
            LOGGER.info("[Breakfront] TaCZ not present, kill bridge runs on vanilla tier only");
        }

        CommandRegistrationCallback.EVENT.register(BreakfrontCommands::register);
        CommandRegistrationCallback.EVENT.register(SectorEditCommands::register);

        LOGGER.info("[Breakfront] server hooks registered");
    }

    /** 实体所属阵营：玩家查 TeamManager；NPC 读 bf.side 标签；未知返回 -1。 */
    private static int sideOfEntity(LivingEntity e) {
        if (e instanceof ServerPlayerEntity p && match != null) {
            Side s = match.teams().sideOf(p.getUuid());
            return s == null ? -1 : s.ordinal();
        }
        if (e.getCommandTags().contains("bf.side.att")) {
            return 0;
        }
        if (e.getCommandTags().contains("bf.side.def")) {
            return 1;
        }
        return -1;
    }

    /** 击杀流广播（服务端线程调用）。 */
    public static void notifyKill(KillListener.KillEntry entry) {
        if (currentServer == null) {
            return;
        }
        KillFeedPayload payload = new KillFeedPayload(
                entry.killer(), entry.victim(), entry.attackerDied(), entry.headshot());
        for (ServerPlayerEntity player : currentServer.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    public static ServerMatch match() {
        return match;
    }

    private static void sendAuthResult(ServerPlayerEntity p, int op, boolean ok,
                                       String user, String roles, String msg) {
        if (p != null && p.networkHandler != null) {
            ServerPlayNetworking.send(p,
                    new com.breakfront.net.AuthResultPayload(op, ok, user, roles, msg));
        }
    }

    /** 命中反馈发送（服务端线程）。kind: 0=命中 2=击杀。 */
    public static void sendHitMarker(ServerPlayerEntity p, int kind) {
        if (p == null || p.networkHandler == null) {
            return;
        }
        ServerPlayNetworking.send(p, new com.breakfront.net.HitMarkerPayload(kind));
    }

    public static MinecraftServer server() {
        return currentServer;
    }
}
