package com.breakfront.client;

import com.breakfront.client.bf.BfServerConfig;
import com.breakfront.client.hud.BreakfrontHud;
import com.breakfront.client.hud.SectorPreviewRenderer;
import com.breakfront.client.hud.WorldZoneRings;
import com.breakfront.client.input.BfKeyBindings;
import com.breakfront.client.state.ClientMatchState;
import com.breakfront.client.state.SectorEditState;
import com.breakfront.client.ui.BfBootstrapScreen;
import com.breakfront.client.ui.BfDeployScreen;
import com.breakfront.client.ui.BreakfrontMainMenu;
import com.breakfront.client.ui.vanilla.BfPauseScreen;
import com.breakfront.net.KillFeedPayload;
import com.breakfront.net.MatchStatePayload;
import com.breakfront.net.ScoreboardPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Breakfront Client —— 客户端入口（BF2042 UI 翻新版）。
 */
public class BreakfrontClient implements ClientModInitializer {

    public static final String MOD_ID = "breakfront-client";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static int lastDeployPhaseChange = -1;

    /** 本进程只跑一次启动预检（BootstrapScreen），完成后进主菜单。 */
    private static volatile boolean bootstrapped = false;

    /** 本进程只在「进入游戏后」提示一次 Geekhonize 登录（关闭 = 稍后再说，本次不再弹）。 */
    private static volatile boolean loginPrompted = false;

    /**
     * 注册 BREAKFRONT 专属 UI 材质包（内置资源包，始终启用）。
     *
     * <p>贴图由 {@code scripts/gen_bf_ui_pack.py} 生成到 mod jar 内的
     * {@code resourcepacks/bf_ui/}，设计令牌与 {@link com.breakfront.client.bf.BfTheme} 同源：
     * 深空冷底 #070B12 / 面板 #131E2C / 描边 #22364A / 荧光青 #35E6D2。只改 GUI
     * （按钮/滑块/输入框/页签/勾选/滚动条/平铺底/分隔线），**不动任何方块贴图**。
     *
     * <p>为什么走「内置资源包」而不是把 {@code assets/minecraft/...} 直接放进模组根：
     * 后者依赖「模组资源包能否覆盖原版命名空间」这一实现细节；前者是 Fabric 官方 API，
     * 资源包由 {@code ResourcePackManager} 排在原版包之后应用，覆盖关系是确定的。
     */
    private static void registerUiPack() {
        Identifier id = Identifier.of(MOD_ID, "bf_ui");
        FabricLoader.getInstance().getModContainer(MOD_ID).ifPresentOrElse(
                container -> {
                    boolean ok = ResourceManagerHelper.registerBuiltinResourcePack(
                            id, container, "resourcepacks/bf_ui",
                            ResourcePackActivationType.ALWAYS_ENABLED);
                    LOGGER.info("[Breakfront] UI 材质包（bf_ui）注册{}", ok ? "成功" : "失败");
                },
                () -> LOGGER.warn("[Breakfront] 未找到自身 ModContainer，UI 材质包未注册"));
    }

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Breakfront] client initialized (BF2042 UI)");

        registerUiPack();

        // 注意：GeoAuth payload（AuthLoginPayload/AuthResultPayload）已在 core 的 Net.java
        // 公共入口注册过——客户端进程里 core 同样会执行该注册，此处不可重复注册，
        // 否则 PayloadTypeRegistry 抛 "already registered" 直接崩客户端。

        BfServerConfig.load();

        // BF2042 按键布局：注册一整套动作键位 + 首次进入游戏自动把原版键位重排成 BF2042
        BfKeyBindings.register();
        registerKeybindCommands();

        // 主菜单接管 + 回合 COUNTDOWN 自动弹部署界面（每阶段变化仅一次）+ 死亡替换为部署重生页
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // 【进入游戏即提示登录】——是「进入游戏」而不是「加入服务器」：主菜单一出现
            // 就检查本地令牌，没有就把登录屏压上去。这样玩家在进任何 BF 服**之前**登录
            // 就已就绪，不会被服务端的登录门禁拦在门外。每会话只提示一次，关闭即视为
            // 「稍后再说」（仍可用 /geo ui 或主菜单重新打开）。
            if (!loginPrompted && client.currentScreen instanceof BreakfrontMainMenu) {
                loginPrompted = true;
                com.breakfront.client.geo.GeoSession.load();
                if (!com.breakfront.client.geo.GeoSession.signedIn()) {
                    client.setScreen(new com.breakfront.client.ui.BfGeoLoginScreen(client.currentScreen));
                }
            }
            if (client.currentScreen instanceof TitleScreen) {
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

            // 首次进入游戏世界即自动套用 BF2042 按键布局（幂等：只做一次，详见 BfKeyBindings）
            BfKeyBindings.ensureAppliedOnce(client);

            if (client.currentScreen != null) {
                net.minecraft.client.gui.screen.Screen cur = client.currentScreen;
                boolean isPause = cur instanceof GameMenuScreen
                        || cur.getClass().getName().endsWith("GameMenuScreen")
                        || cur.getClass().getName().endsWith("PauseScreen");
                if (isPause) {
                    client.setScreen(new BfPauseScreen(cur));
                    return;
                }
            }

            int ph = ClientMatchState.phaseOrdinal();
            if (ph == 1) {
                if (lastDeployPhaseChange != ClientMatchState.phaseChangeCount()) {
                    lastDeployPhaseChange = ClientMatchState.phaseChangeCount();
                    client.setScreen(new BfDeployScreen(false));
                }
            } else if (ph == 2) {
                if (client.currentScreen instanceof BfDeployScreen s) {
                    if (client.player.isAlive() || (!s.isRespawnMode())) {
                        s.close();
                    }
                } else if (client.player.isDead()
                        && client.currentScreen instanceof net.minecraft.client.gui.screen.DeathScreen) {
                    client.setScreen(new BfDeployScreen(true));
                }
            } else if (client.currentScreen instanceof BfDeployScreen) {
                client.currentScreen.close();
            }
        });

        // S2C 接收
        ClientPlayNetworking.registerGlobalReceiver(MatchStatePayload.ID,
                (payload, context) -> context.client().execute(() -> ClientMatchState.applyMatch(payload)));
        ClientPlayNetworking.registerGlobalReceiver(KillFeedPayload.ID,
                (payload, context) -> context.client().execute(() -> ClientMatchState.applyKill(payload)));
        ClientPlayNetworking.registerGlobalReceiver(com.breakfront.net.PlayerPosPayload.ID,
                (payload, context) -> context.client().execute(() -> ClientMatchState.applyFriends(payload)));
        ClientPlayNetworking.registerGlobalReceiver(ScoreboardPayload.ID,
                (payload, context) -> context.client().execute(() -> ClientMatchState.applyScoreboard(payload)));
        ClientPlayNetworking.registerGlobalReceiver(com.breakfront.net.HitMarkerPayload.ID,
                (payload, context) -> context.client().execute(() -> ClientMatchState.applyHit(payload)));
        ClientPlayNetworking.registerGlobalReceiver(com.breakfront.net.SectorEditPayload.ID,
                (payload, context) -> context.client().execute(() -> SectorEditState.apply(payload)));

        // GeoAuth 结果接收：绑定/注销回执 → 聊天提示
        ClientPlayNetworking.registerGlobalReceiver(com.breakfront.net.AuthResultPayload.ID,
                (payload, context) -> context.client().execute(() -> {
                    var mc = context.client();
                    if (mc.player == null) {
                        return;
                    }
                    String roles = payload.rolesCsv().isEmpty() ? ""
                            : "（" + payload.rolesCsv() + "）";
                    mc.player.sendMessage(net.minecraft.text.Text.literal("[Geekhonize] "
                            + (payload.ok() ? "§a" : "§c") + payload.message()
                            + (payload.ok() && payload.username() != null
                            && !payload.username().isEmpty()
                            ? " §7" + payload.username() + roles : "")), false);
                }));

        // GeoAuth：进服自动携带本地令牌绑定账号（若有）
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> {
                    com.breakfront.client.geo.GeoSession.load();
                    String tok = com.breakfront.client.geo.GeoSession.token();
                    if (!tok.isEmpty() && client.getNetworkHandler() != null) {
                        client.execute(() -> {
                            if (client.getNetworkHandler() != null) {
                                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
                                        .send(new com.breakfront.net.AuthLoginPayload(tok));
                            }
                        });
                    }
                });

        // GeoAuth 命令：/geo login|register|sendcode|ui|logout|who
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> dispatcher.register(
                        net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("geo")
                                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("login")
                                        .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
                                                .argument("username", com.mojang.brigadier.arguments.StringArgumentType.word())
                                                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
                                                        .argument("password", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                                        .executes(ctx -> {
                                                            geoLogin(
                                                                    com.mojang.brigadier.arguments.StringArgumentType
                                                                            .getString(ctx, "username"),
                                                                    com.mojang.brigadier.arguments.StringArgumentType
                                                                            .getString(ctx, "password"),
                                                                    MinecraftClient.getInstance());
                                                            return 1;
                                                        }))))
                                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("register")
                                        .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
                                                .argument("username", com.mojang.brigadier.arguments.StringArgumentType.word())
                                                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
                                                        .argument("email", com.mojang.brigadier.arguments.StringArgumentType.word())
                                                        .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
                                                                .argument("code", com.mojang.brigadier.arguments.StringArgumentType.word())
                                                                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
                                                                        .argument("password", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                                                        .executes(ctx -> {
                                                                            geoRegister(
                                                                                    com.mojang.brigadier.arguments.StringArgumentType
                                                                                            .getString(ctx, "username"),
                                                                                    com.mojang.brigadier.arguments.StringArgumentType
                                                                                            .getString(ctx, "email"),
                                                                                    com.mojang.brigadier.arguments.StringArgumentType
                                                                                            .getString(ctx, "code"),
                                                                                    com.mojang.brigadier.arguments.StringArgumentType
                                                                                            .getString(ctx, "password"),
                                                                                    MinecraftClient.getInstance());
                                                                            return 1;
                                                                        }))))))
                                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("sendcode")
                                        .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
                                                .argument("email", com.mojang.brigadier.arguments.StringArgumentType.word())
                                                .executes(ctx -> {
                                                    geoSendCode(
                                                            com.mojang.brigadier.arguments.StringArgumentType
                                                                    .getString(ctx, "email"),
                                                            MinecraftClient.getInstance());
                                                    return 1;
                                                })))
                                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("logout")
                                        .executes(ctx -> {
                                            com.breakfront.client.geo.GeoSession.clear();
                                            var c = MinecraftClient.getInstance();
                                            if (c.getNetworkHandler() != null) {
                                                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
                                                        .send(new com.breakfront.net.AuthLoginPayload(""));
                                            }
                                            if (c.player != null) {
                                                c.player.sendMessage(net.minecraft.text.Text.literal(
                                                        "[Geekhonize] 已清除本地登录"), false);
                                            }
                                            return 1;
                                        }))
                                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("who")
                                        .executes(ctx -> {
                                            var c = MinecraftClient.getInstance();
                                            if (c.player != null) {
                                                String u = com.breakfront.client.geo.GeoSession.username();
                                                c.player.sendMessage(net.minecraft.text.Text.literal(
                                                        "[Geekhonize] " + (u.isEmpty()
                                                                ? "未登录（/geo login <用户名> <密码>；注册需邮箱验证码，网页端注册更佳）"
                                                                : "已登录：" + u)), false);
                                            }
                                            return 1;
                                        }))
                                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("ui")
                                        .executes(ctx -> {
                                            var c = MinecraftClient.getInstance();
                                            if (!(c.currentScreen
                                                    instanceof com.breakfront.client.ui.BfGeoLoginScreen)) {
                                                c.setScreen(new com.breakfront.client.ui.BfGeoLoginScreen(
                                                        c.currentScreen));
                                            }
                                            return 1;
                                        }))));

        HudRenderCallback.EVENT.register(new BreakfrontHud()::render);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(WorldZoneRings::render);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(
                com.breakfront.client.hud.FriendlyHostileMarks::render);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(SectorPreviewRenderer::render);

        // AI 增援：僵尸实体 → 史蒂夫士兵渲染（玩家模型+默认皮肤+持枪装备）
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(
                net.minecraft.entity.EntityType.ZOMBIE, com.breakfront.client.render.BotSoldierRenderer::new);

        // 情境音乐播放器
        ClientTickEvents.END_CLIENT_TICK.register(
                com.breakfront.client.audio.BfMusicPlayer::tick);
    }

    // ================= /geo 执行 =================

    /** /geo login <user> <pass>。 */
    private static void geoLogin(String user, String pass, MinecraftClient c) {
        if (user == null || pass == null || user.isBlank() || pass.isBlank()) {
            geoMsg(c, "§c用法：/geo login <用户名> <密码>");
            return;
        }
        java.util.concurrent.CompletableFuture
                .supplyAsync(() -> com.breakfront.client.geo.GeoHttp.login(user.trim(), pass.trim()))
                .thenAccept(r -> c.execute(() -> {
                    if (r.ok()) {
                        afterAuthOk(c, r, false);
                    } else {
                        geoMsg(c, "§c" + (r.msg() == null || r.msg().isEmpty() ? "登录失败" : r.msg()));
                    }
                }));
    }

    /** /geo register <user> <email> <code> <pass>。 */
    private static void geoRegister(String user, String email, String code, String pass, MinecraftClient c) {
        if (user == null || email == null || code == null || pass == null
                || user.isBlank() || email.isBlank() || code.isBlank() || pass.isBlank()) {
            geoMsg(c, "§c用法：/geo register <用户名> <邮箱> <验证码> <密码>（验证码用 /geo sendcode <邮箱> 获取）");
            return;
        }
        java.util.concurrent.CompletableFuture
                .supplyAsync(() -> com.breakfront.client.geo.GeoHttp
                        .register(user.trim(), pass.trim(), email.trim(), code.trim()))
                .thenAccept(r -> c.execute(() -> {
                    if (r.ok()) {
                        afterAuthOk(c, r, true);
                    } else {
                        geoMsg(c, "§c" + (r.msg() == null || r.msg().isEmpty() ? "注册失败" : r.msg()));
                    }
                }));
    }

    /** /geo sendcode <email>（注册用验证码）。 */
    private static void geoSendCode(String email, MinecraftClient c) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            geoMsg(c, "§c用法：/geo sendcode <邮箱>");
            return;
        }
        java.util.concurrent.CompletableFuture
                .supplyAsync(() -> com.breakfront.client.geo.GeoHttp.sendCode(email.trim(), "register"))
                .thenAccept(r -> c.execute(() -> {
                    if (r.ok()) {
                        geoMsg(c, "§a验证码已发送" + (r.devCode() == null || r.devCode().isEmpty()
                                ? "" : "（联调：" + r.devCode() + "）"));
                    } else {
                        geoMsg(c, "§c" + (r.msg() == null || r.msg().isEmpty() ? "发送失败" : r.msg()));
                    }
                }));
    }

    private static void afterAuthOk(MinecraftClient c, com.breakfront.client.geo.GeoHttp.Res r, boolean reg) {
        com.breakfront.client.geo.GeoSession.save(r.token(), r.username());
        if (c.getNetworkHandler() != null) {
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
                    .send(new com.breakfront.net.AuthLoginPayload(r.token()));
        }
        geoMsg(c, "§a" + (reg ? "注册并登录成功：" : "登录成功：")
                + r.username() + "（已自动与服务器绑定）");
    }

    /**
     * 客户端命令 {@code /bfkey}：一键套用 / 恢复按键布局。
     * <ul>
     *   <li>{@code /bfkey apply} —— 把原版键位重排成 BF2042 布局（保留已注册的 BF 动作键位）</li>
     *   <li>{@code /bfkey reset} —— 恢复原版 Minecraft 默认键位</li>
     * </ul>
     * 按键是客户端概念，故走 Fabric 客户端命令（服务端无对应指令）。
     */
    private static void registerKeybindCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("bfkey")
                        .then(ClientCommandManager.literal("apply")
                                .executes(ctx -> {
                                    BfKeyBindings.applyBf2042Preset(ctx.getSource().getClient());
                                    ctx.getSource().getClient().player.sendMessage(
                                            net.minecraft.text.Text.literal("§a[Breakfront] 已套用 BF2042 按键布局"),
                                            false);
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("reset")
                                .executes(ctx -> {
                                    BfKeyBindings.resetVanilla(ctx.getSource().getClient());
                                    ctx.getSource().getClient().player.sendMessage(
                                            net.minecraft.text.Text.literal("§a[Breakfront] 已恢复原版 Minecraft 默认键位"),
                                            false);
                                    return 1;
                                }))
        ));
    }

    private static void geoMsg(MinecraftClient c, String text) {
        if (c.player != null) {
            c.player.sendMessage(net.minecraft.text.Text.literal("[Geekhonize] " + text), false);
        }
    }
}
