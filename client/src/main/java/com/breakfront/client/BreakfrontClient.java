package com.breakfront.client;

import com.breakfront.client.bf.BfServerConfig;
import com.breakfront.client.hud.BreakfrontHud;
import com.breakfront.client.hud.SectorPreviewRenderer;
import com.breakfront.client.hud.WorldZoneRings;
import com.breakfront.client.state.AdminState;
import com.breakfront.client.state.ClientMatchState;
import com.breakfront.client.state.SectorEditState;
import com.breakfront.client.ui.BfDeployScreen;
import com.breakfront.client.ui.BreakfrontMainMenu;
import com.breakfront.client.ui.admin.BfAdminGateScreen;
import com.breakfront.client.ui.admin.BfAdminPanel;
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

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Breakfront] client initialized (BF2042 UI)");

        BfServerConfig.load();

        // 主菜单接管 + 回合 COUNTDOWN 自动弹部署界面（每阶段变化仅一次）+ 死亡替换为部署重生页
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.currentScreen instanceof TitleScreen) {
                client.setScreen(new BreakfrontMainMenu());
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

        // S2C 接收：管理员登录结果（M8）→ 门禁屏显示结果/切面板
        ClientPlayNetworking.registerGlobalReceiver(com.breakfront.net.AdminLoginResultPayload.ID,
                (payload, context) -> context.client().execute(
                        () -> AdminState.applyResult(payload.ok(), payload.message())));

        // 管理员面板入口（M8→命令化 2026-09-05）：聊天输入 /bfp 开关门禁/面板
        // 不再用快捷键——F8/F6 均与渲染/镜头功能冲突，命令入口零冲突
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> dispatcher.register(
                        net.minecraft.server.command.CommandManager.literal("bfp").executes(ctx -> {
                            MinecraftClient c = MinecraftClient.getInstance();
                            if (c.currentScreen instanceof TitleScreen || c.currentScreen == null) {
                                if (AdminState.isAdmin()) {
                                    c.setScreen(new BfAdminPanel());
                                } else {
                                    c.setScreen(new BfAdminGateScreen());
                                }
                            } else if (c.currentScreen instanceof BfAdminPanel) {
                                c.setScreen(null); // 再输入 /bfp 收起面板
                            }
                            return 1;
                        })));

        // 登录成功后门禁自动切面板
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.currentScreen instanceof BfAdminGateScreen && AdminState.justGranted()) {
                AdminState.clearJustGranted();
                client.setScreen(new BfAdminPanel());
            }
        });

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
