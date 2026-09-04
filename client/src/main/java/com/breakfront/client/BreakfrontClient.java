package com.breakfront.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Breakfront Client —— 客户端界面入口。
 * P0 阶段仅为可编译占位；后续承载：部署界面、战术计分板、
 * 击杀事件流 HUD、据点/票数 UI、结算界面（矢量绘制，见 charter §4.7）。
 */
public class BreakfrontClient implements ClientModInitializer {

    public static final String MOD_ID = "breakfront-client";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Breakfront] client initialized (P0 scaffold)");
    }
}
