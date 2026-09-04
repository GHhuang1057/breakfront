package com.breakfront.client;

import com.breakfront.client.hud.BreakfrontHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Breakfront Client —— 客户端界面入口。
 * P1：注册最小矢量 HUD 管线（据点进度条 + 击杀事件流 + 票数/计时文本），
 * 全部使用几何图元绘制（无像素贴图），后续按 charter §4.7 扩展为完整 BF2042 界面。
 */
public class BreakfrontClient implements ClientModInitializer {

    public static final String MOD_ID = "breakfront-client";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Breakfront] client initialized (P1)");
        BreakfrontHud hud = new BreakfrontHud();
        HudRenderCallback.EVENT.register(hud::render);
    }
}
