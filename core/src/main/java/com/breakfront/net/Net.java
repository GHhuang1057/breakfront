package com.breakfront.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Payload 类型注册（S2C 在双端注册；由 core 公共入口调用一次）。
 */
public final class Net {

    public static final Logger LOGGER = LoggerFactory.getLogger("breakfront.net");

    private Net() {
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(MatchStatePayload.ID, MatchStatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(KillFeedPayload.ID, KillFeedPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ScoreboardPayload.ID, ScoreboardPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(HitMarkerPayload.ID, HitMarkerPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SectorEditPayload.ID, SectorEditPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SetClassPayload.ID, SetClassPayload.CODEC);
        LOGGER.info("[Breakfront] networking payloads registered");
    }
}
