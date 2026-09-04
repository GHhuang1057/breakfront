package com.breakfront.client;

import com.breakfront.client.hud.BreakfrontHud;
import com.breakfront.client.hud.WorldZoneRings;
import com.breakfront.client.state.ClientMatchState;
import com.breakfront.net.KillFeedPayload;
import com.breakfront.net.MatchStatePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Breakfront Client —— 客户端界面入口。
 * P1：注册 S2C 接收（对局状态/击杀流）+ 战场 HUD（真数据驱动）。
 */
public class BreakfrontClient implements ClientModInitializer {

    public static final String MOD_ID = "breakfront-client";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Breakfront] client initialized (P1 networking)");

        // S2C 接收：对局状态同步帧
        ClientPlayNetworking.registerGlobalReceiver(MatchStatePayload.ID,
                (payload, context) -> context.client().execute(() -> ClientMatchState.applyMatch(payload)));

        // S2C 接收：击杀流
        ClientPlayNetworking.registerGlobalReceiver(KillFeedPayload.ID,
                (payload, context) -> context.client().execute(() -> ClientMatchState.applyKill(payload)));

        HudRenderCallback.EVENT.register(new BreakfrontHud()::render);

        // 据点区域描边（世界空间圆环）——战地式高亮
        WorldRenderEvents.AFTER_TRANSLUCENT.register(WorldZoneRings::render);
    }
}
