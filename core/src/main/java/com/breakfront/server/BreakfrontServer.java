package com.breakfront.server;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务端组件装配器：把对局运行时挂到 Fabric 服务端生命周期。
 * 单例注册（onInitialize 调用一次，dedicated/integrated server 均生效）。
 */
public final class BreakfrontServer {

    public static final Logger LOGGER = LoggerFactory.getLogger("breakfront.server");

    private static ServerMatch match;
    private static boolean registered = false;

    private BreakfrontServer() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            match = new ServerMatch();
            KillListener.bind(match);
            LOGGER.info("[Breakfront] server match ready ({} zones, {} sectors)",
                    match.game().zoneCount(), match.game().sectors().size());
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (match != null) {
                match.tick(server);
            }
        });

        // 击杀归属桥 · 第 1 层：vanilla 死亡事件（覆盖全部死因，负责扣票）
        ServerLivingEntityEvents.AFTER_DEATH.register(KillListener::onEntityDeath);

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

        LOGGER.info("[Breakfront] server hooks registered");
    }

    public static ServerMatch match() {
        return match;
    }
}
