package com.breakfront;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Breakfront Core —— 服务端权威规则层入口。
 * P0 阶段仅为可编译占位；后续承载：回合状态机、攻防规则、票数、
 * 兵种装备发放、破坏回滚、击杀归属桥等（见 docs/bf-modpack-charter.md）。
 */
public class Breakfront implements ModInitializer {

    public static final String MOD_ID = "breakfront";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[Breakfront] core initialized (P0 scaffold)");
    }
}
