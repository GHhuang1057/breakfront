package com.breakfront.client;

import com.breakfront.client.bf.BfServerConfig;
import com.breakfront.client.hud.BreakfrontHud;
import com.breakfront.client.hud.SectorPreviewRenderer;
import com.breakfront.client.hud.WorldZoneRings;
import com.breakfront.client.state.ClientMatchState;
import com.breakfront.client.state.SectorEditState;
import com.breakfront.client.ui.BfBootstrapScreen;
import com.breakfront.client.ui.BfDeployScreen;
import com.breakfront.client.ui.BreakfrontMainMenu;
import com.breakfront.net.KillFeedPayload;
import com.breakfront.net.MatchStatePayload;
import com.breakfront.net.ScoreboardPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.gui.screen.TitleScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Breakfront Client —— 客户端入口（BF2042 UI 翻新版）。
 *
 * 职责：
 * - 主菜单替换：原版 TitleScreen 出现即切换为 BREAKFRONT 主菜单（一键直连，无服务器选择）
 * - S2C 接收：对局状态/击杀流 → ClientMatchState → HUD
 * - 世界渲染：据点区域描边环
 */
public class BreakfrontClient implements ClientModInitializer {

    public static final String MOD_ID = "breakfront-client";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static int lastDeployPhaseChange = -1;

    /** 本进程只跑一次启动预检（BootstrapScreen），完成后进主菜单。 */
    private static volatile boolean bootstrapped = false;

    /** 管理控制台已独立为 Web 程序（core /bfadmin），客户端不再内置任何管理 UI。 */

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Breakfront] client initialized (BF2042 UI)");

        BfServerConfig.load();

        // 主菜单接管 + 回合 COUNTDOWN 自动弹部署界面（每阶段变化仅一次）+ 死亡替换为部署重生页
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.currentScreen instanceof TitleScreen) {
                // 首次进入：先跑「启动预检屏」（更新+音频下载进度），完成后自动进主菜单
                if (!bootstrapped) {
                    bootstrapped = true;
                    client.setScreen(new BfBootstrapScreen());
                } else {
                    client.setScreen(new BreakfrontMainMenu());
                }
                return;
            }
            if (client.player == null || client.world == null) {
                return;
            }
            int ph = ClientMatchState.phaseOrdinal();
            if (ph == 1) {
                if (lastDeployPhaseChange != ClientMatchState.phaseChangeCount()) {
                    lastDeployPhaseChange = ClientMatchState.phaseChangeCount();
                    client.setScreen(new BfDeployScreen(false));
                }
            } else if (ph == 2) {
                // 战斗中：部署/阵亡页处理
                if (client.currentScreen instanceof BfDeployScreen s) {
                    if (client.player.isAlive() || (!s.isRespawnMode())) {
                        s.close();
                    }
                } else if (client.player.isDead()
                        && client.currentScreen instanceof net.minecraft.client.gui.screen.DeathScreen) {
                    client.setScreen(new BfDeployScreen(true)); // 阵亡 → BF 部署页
                }
            } else if (client.currentScreen instanceof BfDeployScreen) {
                client.currentScreen.close();
            }
        });

        // S2C 接收：对局状态同步帧
        ClientPlayNetworking.registerGlobalReceiver(MatchStatePayload.ID,
                (payload, context) -> context.client().execute(() -> ClientMatchState.applyMatch(payload)));

        // S2C 接收：击杀流
        ClientPlayNetworking.registerGlobalReceiver(KillFeedPayload.ID,
                (payload, context) -> context.client().execute(() -> ClientMatchState.applyKill(payload)));

        // S2C 接收：位置帧（雷达友军点）
        ClientPlayNetworking.registerGlobalReceiver(com.breakfront.net.PlayerPosPayload.ID,
                (payload, context) -> context.client().execute(() -> ClientMatchState.applyFriends(payload)));

        // S2C 接收：比分/击杀榜
        ClientPlayNetworking.registerGlobalReceiver(ScoreboardPayload.ID,
                (payload, context) -> context.client().execute(() -> ClientMatchState.applyScoreboard(payload)));

        // S2C 接收：命中反馈（HitMarker）
        ClientPlayNetworking.registerGlobalReceiver(com.breakfront.net.HitMarkerPayload.ID,
                (payload, context) -> context.client().execute(() -> ClientMatchState.applyHit(payload)));

        // S2C 接收：扇区编辑器预览（/bfs 会话）
        ClientPlayNetworking.registerGlobalReceiver(com.breakfront.net.SectorEditPayload.ID,
                (payload, context) -> context.client().execute(() -> SectorEditState.apply(payload)));

        HudRenderCallback.EVENT.register(new BreakfrontHud()::render);

        // 据点区域描边（世界空间方形亮边）——战地式高亮
        WorldRenderEvents.AFTER_TRANSLUCENT.register(WorldZoneRings::render);

        // 战场士兵标记（BF ESP）：友军蓝菱形穿墙可见 / 敌军红菱形被墙遮挡
        WorldRenderEvents.AFTER_TRANSLUCENT.register(
                com.breakfront.client.hud.FriendlyHostileMarks::render);

        // 扇区编辑器地面预览（世界空间圆环，按扇区分色）
        WorldRenderEvents.AFTER_TRANSLUCENT.register(SectorPreviewRenderer::render);

        // AI 增援：僵尸实体 → 史蒂夫士兵渲染（玩家模型+默认皮肤+持枪装备）
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(
                net.minecraft.entity.EntityType.ZOMBIE, com.breakfront.client.render.BotSoldierRenderer::new);
    }
}
