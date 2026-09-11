package com.breakfront.server;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * /bfs —— 游戏内「地图扇区编辑器」管理指令（权限 2，单人/联机服务端通用）。
 *
 * 目标：在已装载的地图（含外部导入如 Metro）上，用准星逐点圈出据点与扇区，
 * 生成可落盘/可热应用的扇区配置（breakfront/sectors.json），替代硬编码坐标。
 *
 * 用法总览（/bfs help 亦有）：
 *   on / off                      打开/关闭编辑器（本玩家可见地面圆环预览）
 *   here [半径]                   在准星所指方块位置，向当前扇区添加一个据点(默认 r=8)
 *   move &lt;id&gt;                   把该据点圆心移到准星处
 *   resize &lt;id&gt; &lt;半径&gt;          改据点半径
 *   remove &lt;id&gt; / undo           删指定据点 / 撤销最近添加
 *   sector next|prev|list|name &lt;名&gt;  切换/查看/重命名当前编辑扇区
 *   list / status                 查看布局与编辑器状态
 *   clear                         清空布局重新划分
 *   save / load / apply           落盘 / 从盘装载 / 应用(保存并重建对局回大厅)
 */
public final class SectorEditCommands {

    private static final double DEFAULT_RADIUS = 8.0;

    private SectorEditCommands() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                CommandRegistryAccess registryAccess,
                                CommandManager.RegistrationEnvironment environment) {
        LiteralArgumentBuilder<ServerCommandSource> root = literal("bfs")
                .requires(s -> AdminService.allows(s)); // op2 或 管理员会话（M8）

        root.then(simple("on", (src, match) -> {
            ServerPlayerEntity p = playerOf(src);
            if (p == null) {
                return "仅玩家可用";
            }
            return match.editorOn(src.getServer(), p.getUuid());
        }));
        root.then(simple("off", (src, match) -> {
            ServerPlayerEntity p = playerOf(src);
            if (p == null) {
                return "仅玩家可用";
            }
            return match.editorOff(src.getServer(), p.getUuid());
        }));
        root.then(simple("status", (src, match) -> "编辑器: " + (match.editorViewersCount() > 0
                ? "开(" + match.editorViewersCount() + "人观看)" : "关")
                + " ｜ 当前扇区: " + match.currentEditorSectorName()
                + " ｜ AI填充: " + (match.autoFillEnabled() ? "开" : "关")
                + " ｜ 布局: " + match.layout().sectorCount() + "扇区/" + match.layout().zoneCount() + "据点"
                + " ｜ 出生点: " + (match.layout().hasAttackerSpawn() ? "攻✓" : "攻✗")
                + "/" + (match.layout().hasDefenderSpawn() ? "守✓" : "守✗")
                + (match.layout().hasLobbySpawn() ? "/大厅✓" : "")));
        root.then(simple("list", (src, match) -> match.editorLayoutText()));

        // /bfs here [radius]
        LiteralArgumentBuilder<ServerCommandSource> here = literal("here")
                .executes(ctx -> runHere(ctx.getSource(), DEFAULT_RADIUS));
        here.then(CommandManager.argument("radius", DoubleArgumentType.doubleArg(1.0, 64.0))
                .executes(ctx -> runHere(ctx.getSource(),
                        DoubleArgumentType.getDouble(ctx, "radius"))));
        root.then(here);

        root.then(moveNode());
        root.then(resizeNode());
        root.then(removeNode());
        root.then(simple("undo", (src, match) -> match.editorUndo(src.getServer())));
        root.then(simple("clear", (src, match) -> match.editorClear(src.getServer())));

        root.then(sectorNode());
        root.then(spawnNode());
        root.then(simple("save", (src, match) -> match.saveLayout()));
        root.then(simple("load", (src, match) -> match.loadLayout(src.getServer())));
        root.then(simple("apply", (src, match) -> match.applyLayout(src.getServer())));
        root.then(simple("help", (src, match) -> helpText()));

        dispatcher.register(root);
    }

    // ---------- 节点构建 ----------

    /** 简单叶子节点：无需额外参数。 */
    private static LiteralArgumentBuilder<ServerCommandSource> simple(String name,
                                                                      Op action) {
        return literal(name).executes(ctx -> {
            var match = BreakfrontServer.match();
            if (match == null) {
                ctx.getSource().sendError(Text.literal("对局尚未初始化"));
                return 0;
            }
            send(ctx.getSource(), action.run(ctx.getSource(), match));
            return 1;
        });
    }

    private static LiteralArgumentBuilder<ServerCommandSource> moveNode() {
        return literal("move")
                .then(CommandManager.argument("id", StringArgumentType.word())
                        .executes(ctx -> {
                            var match = BreakfrontServer.match();
                            if (match == null) {
                                return 0;
                            }
                            ServerPlayerEntity p = playerOf(ctx.getSource());
                            if (p == null) {
                                send(ctx.getSource(), "仅玩家可用");
                                return 1;
                            }
                            double[] c = lookAtCenter(p);
                            if (c == null) {
                                ctx.getSource().sendError(Text.literal("请把准星对准一个方块"));
                                return 0;
                            }
                            String id = StringArgumentType.getString(ctx, "id");
                            send(ctx.getSource(), match.editorMove(ctx.getSource().getServer(), id, c[0], c[1]));
                            return 1;
                        }));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> resizeNode() {
        return literal("resize")
                .then(CommandManager.argument("id", StringArgumentType.word())
                        .then(CommandManager.argument("radius", DoubleArgumentType.doubleArg(1.0, 64.0))
                                .executes(ctx -> {
                                    var match = BreakfrontServer.match();
                                    if (match == null) {
                                        return 0;
                                    }
                                    String id = StringArgumentType.getString(ctx, "id");
                                    double r = DoubleArgumentType.getDouble(ctx, "radius");
                                    send(ctx.getSource(), match.editorResize(ctx.getSource().getServer(), id, r));
                                    return 1;
                                })));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> removeNode() {
        return literal("remove")
                .then(CommandManager.argument("id", StringArgumentType.word())
                        .executes(ctx -> {
                            var match = BreakfrontServer.match();
                            if (match == null) {
                                return 0;
                            }
                            String id = StringArgumentType.getString(ctx, "id");
                            send(ctx.getSource(), match.editorRemove(ctx.getSource().getServer(), id));
                            return 1;
                        }));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> sectorNode() {
        return literal("sector")
                .then(literal("next").executes(ctx -> {
                    var match = BreakfrontServer.match();
                    if (match == null) {
                        return 0;
                    }
                    send(ctx.getSource(), match.editorSectorNext(ctx.getSource().getServer()));
                    return 1;
                }))
                .then(literal("prev").executes(ctx -> {
                    var match = BreakfrontServer.match();
                    if (match == null) {
                        return 0;
                    }
                    send(ctx.getSource(), match.editorSectorPrev(ctx.getSource().getServer()));
                    return 1;
                }))
                .then(literal("list").executes(ctx -> {
                    var match = BreakfrontServer.match();
                    if (match == null) {
                        return 0;
                    }
                    send(ctx.getSource(), match.editorLayoutText());
                    return 1;
                }))
                .then(literal("name")
                        .then(CommandManager.argument("name", StringArgumentType.string())
                                .executes(ctx -> {
                                    var match = BreakfrontServer.match();
                                    if (match == null) {
                                        return 0;
                                    }
                                    String name = StringArgumentType.getString(ctx, "name");
                                    send(ctx.getSource(), match.editorSectorRename(ctx.getSource().getServer(), name));
                                    return 1;
                                })));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> spawnNode() {
        return literal("spawn")
                .then(literal("att").executes(ctx -> runSetSpawn(ctx.getSource(), true)))
                .then(literal("def").executes(ctx -> runSetSpawn(ctx.getSource(), false)))
                .then(literal("lobby").executes(ctx -> runSetLobbySpawn(ctx.getSource())));
    }

    private static int runSetSpawn(ServerCommandSource src, boolean attacker) {
        var match = BreakfrontServer.match();
        if (match == null) {
            src.sendError(Text.literal("对局尚未初始化"));
            return 0;
        }
        ServerPlayerEntity p = playerOf(src);
        if (p == null) {
            src.sendError(Text.literal("仅玩家可用"));
            return 0;
        }
        double[] c = lookAtCenter(p);
        if (c == null) {
            src.sendError(Text.literal("请把准星对准一个方块（目标超出 160 格或对准天空）"));
            return 0;
        }
        send(src, match.editorSetSpawn(src.getServer(), attacker, c[0], c[1]));
        return 1;
    }

    private static int runSetLobbySpawn(ServerCommandSource src) {
        var match = BreakfrontServer.match();
        if (match == null) {
            src.sendError(Text.literal("对局尚未初始化"));
            return 0;
        }
        ServerPlayerEntity p = playerOf(src);
        if (p == null) {
            src.sendError(Text.literal("仅玩家可用"));
            return 0;
        }
        double[] c = lookAtCenter(p);
        if (c == null) {
            src.sendError(Text.literal("请把准星对准一个方块（目标超出 160 格或对准天空）"));
            return 0;
        }
        send(src, match.editorSetLobbySpawn(src.getServer(), c[0], c[1]));
        return 1;
    }

    private static int runHere(ServerCommandSource src, double radius) {
        var match = BreakfrontServer.match();
        if (match == null) {
            src.sendError(Text.literal("对局尚未初始化"));
            return 0;
        }
        ServerPlayerEntity p = playerOf(src);
        if (p == null) {
            src.sendError(Text.literal("仅玩家可用"));
            return 0;
        }
        double[] c = lookAtCenter(p);
        if (c == null) {
            src.sendError(Text.literal("请把准星对准一个方块（目标超出 160 格或对准天空）"));
            return 0;
        }
        send(src, match.editorAdd(src.getServer(), c[0], c[1], radius));
        return 1;
    }

    // ---------- 工具 ----------

    @FunctionalInterface
    private interface Op {
        String run(ServerCommandSource src, ServerMatch match);
    }

    private static ServerPlayerEntity playerOf(ServerCommandSource src) {
        return src.getEntity() instanceof ServerPlayerEntity p ? p : null;
    }

    /** 准星射线命中方块 → 返回 {x+0.5, z+0.5}；未命中返回 null。 */
    private static double[] lookAtCenter(ServerPlayerEntity player) {
        HitResult hit = player.raycast(160.0, 0.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = ((net.minecraft.util.hit.BlockHitResult) hit).getBlockPos();
        return new double[]{pos.getX() + 0.5, pos.getZ() + 0.5};
    }

    private static void send(ServerCommandSource source, String message) {
        source.sendFeedback(() -> Text.literal(message), false);
    }

    private static String helpText() {
        return String.join("\n",
                "—— /bfs 扇区编辑器（准星圈点）——",
                "on / off                     开/关编辑器（地面圆环预览）",
                "here [半径=8]                在准星所指处向当前扇区添加据点",
                "move <id>                    移动该据点圆心到准星处",
                "resize <id> <半径>           调整据点半径",
                "remove <id> | undo           删除指定 / 撤销最近添加",
                "sector next|prev|list|name <名>  切换/查看/重命名当前扇区",
                "spawn att|def|lobby           在准星所指处设置攻/守/大厅出生点(进布局)",
                "clear                        清空布局重新划分",
                "save | load | apply          落盘 / 装载 / 应用(保存+重建对局)",
                "例：/bfs on → 找好位置 → /bfs here 10 → 往前走 /bfs sector next → /bfs here …");
    }
}
