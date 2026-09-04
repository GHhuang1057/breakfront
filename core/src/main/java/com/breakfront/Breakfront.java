package com.breakfront;

import com.breakfront.net.Net;
import com.breakfront.server.BreakfrontServer;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Breakfront Core —— 服务端权威规则层入口。
 * P1：装配服务端对局运行时（BreakthroughGame + 阵营 + 击杀归属桥 + /bf 指令）。
 * 后续承载：地图配置加载、破坏回滚、兵种装备发放等（见 docs/bf-modpack-charter.md）。
 */
public class Breakfront implements ModInitializer {

    public static final String MOD_ID = "breakfront";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[Breakfront] core initialized (P1)");
        Net.register();              // Payload 类型（S2C 双端注册）
        BreakfrontServer.register(); // 服务端生命周期/指令/击杀桥
    }
}
