package com.breakfront.client.hud;

import com.breakfront.client.state.ClientMatchState;
import com.breakfront.client.state.ClientMatchState.BoardRow;
import com.breakfront.client.state.ClientMatchState.FriendDot;
import com.breakfront.client.util.BotNames;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 战场士兵标记层（BF 式 ESP，2026-09-05 v1）——不修改任何实体渲染，只做叠加标记：
 *
 * - 友军（同阵营真人玩家 + AI 增援）：头顶淡蓝菱形，绕过深度测试 → 穿墙可见；
 *   有方块遮挡时降低透明度，直观区分「直视 / 隔墙」。
 * - 敌军：头顶红色菱形，保留深度测试 → 不可穿墙，被墙/掩体挡住即消失。
 * - 自身不渲染标记。
 *
 * 阵营判定（无新增协议）：
 *   - 主来源 {@link ClientMatchState#friends()}：PlayerPosPayload 每 0.25s 刷新，
 *     含**全部在线单位（真人 + AI 假玩家）**的 name→side —— 大厅阶段（无击杀、
 *     战绩榜为空）同样可靠；
 *   - 次来源 ClientMatchState.board()：只登记参与过击杀的人，作兜底；
 *   - 末位兜底：AI 假玩家命名 BF_A<序号>/BF_D<序号>（见 BotSquad#spawn），
 *     按前缀即时判别（刚进服、位置帧尚未到达的头 0.25s 也不会漏标）。
 *
 * 深度状态同批冲突：敌我分两遍提交（友军 pass disableDepthTest，敌军 pass 默认深度）。
 * 遮挡 raycast 每实体 250ms 节流（墙后淡化/敌人跳过）。距离 44m 内显示、随距离淡出。
 * 渲染路径：vanilla immediate（POSITION_COLOR），AFTER_TRANSLUCENT，兼容 Sodium/Iris。
 */
public final class FriendlyHostileMarks {

    private static final double MAX_DIST = 56.0;
    private static final double RAY_LIFT = 1.35; // 标记视线起点（玩家眼部）
    private static final int FRIENDLY = 0xFF4DA6FF;
    private static final int HOSTILE = 0xFFFF5A52;

    /** raycast 结果节流缓存（entityId -> blocked / 上次判定时间）。 */
    private static final Map<Integer, RayCache> rayCache = new HashMap<>();
    private static final long RAY_INTERVAL_MS = 250;

    private record RayCache(boolean blocked, long atMs) {
    }

    private FriendlyHostileMarks() {
    }

    public static void render(WorldRenderContext context) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        if (world == null || mc.player == null || mc.getCameraEntity() == null) {
            return;
        }
        int phase = ClientMatchState.phaseOrdinal();
        if (phase != 1 && phase != 2) {
            return; // 仅对局阶段显示
        }
        int selfSide = selfSide();
        if (selfSide < 0) {
            return; // 尚未入队（大厅/未分配）不显示任何标记
        }

        var cam = mc.gameRenderer.getCamera();
        Vec3d camPos = cam.getPos();
        // 相机基向量（同 ZoneMarkers 投影数学）
        float yawR = (float) Math.toRadians(cam.getYaw());
        float pitchR = (float) Math.toRadians(cam.getPitch());
        Vec3d look = new Vec3d(
                -Math.sin(yawR) * Math.cos(pitchR),
                -Math.sin(pitchR),
                Math.cos(yawR) * Math.cos(pitchR)).normalize();
        Vec3d right = look.crossProduct(new Vec3d(0, 1, 0)).normalize();
        Vec3d up = right.crossProduct(look).normalize();

        // 扫描战场角色（真人 + AI 增援），一次取回两遍共用
        var scanBox = mc.player.getBoundingBox().expand(MAX_DIST + 8);
        // AI 假玩家已是**真实 ServerPlayerEntity 壳**（BotSquad），故只需扫玩家实体
        var actors = world.getEntitiesByClass(Entity.class, scanBox,
                e -> e instanceof PlayerEntity);

        // 友军 pass（穿墙）：先关深度再画；敌军 pass（被墙挡）：恢复深度再画
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        drawPass(actors, camPos, look, right, up, context.positionMatrix(), selfSide, true);
        RenderSystem.enableDepthTest();
        drawPass(actors, camPos, look, right, up, context.positionMatrix(), selfSide, false);
        RenderSystem.disableBlend();

        // 清理离开视野距离过久的 ray 缓存
        long now = System.currentTimeMillis();
        rayCache.entrySet().removeIf(e2 -> now - e2.getValue().atMs() > 3000);
    }

    /** 单遍绘制：friendlyOnly=true 友军（disableDepthTest 已在外部设置），false 敌军。 */
    private static void drawPass(List<Entity> actors, Vec3d camPos, Vec3d look, Vec3d right, Vec3d up,
                                 Matrix4f m, int selfSide, boolean friendlyOnly) {
        long now = System.currentTimeMillis();
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        BufferBuilder buf = Tessellator.getInstance()
                .begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        boolean wrote = false;

        for (Entity e : actors) {
            if (e == mc.player) {
                continue;
            }
            if (!(e instanceof PlayerEntity pe)) {
                continue;
            }
            // 用 GameProfile 名（稳定、与位置帧/战绩榜同名）而非展示名（可能带队伍前缀）
            int side = sideOfName(pe.getGameProfile().getName());
            if (side < 0) {
                continue; // 未分配/查不到阵营：不标
            }
            boolean friendly = side == selfSide;
            if (friendly != friendlyOnly) {
                continue;
            }

            double dist = e.getPos().distanceTo(camPos);
            if (dist > MAX_DIST || dist < 0.8) {
                continue;
            }

            Vec3d base = new Vec3d(e.getX(), e.getBoundingBox().maxY + 0.55, e.getZ());
            Vec3d to = base.subtract(camPos);
            double distSq = to.lengthSquared();
            if (to.normalize().dotProduct(look) < 0.02) {
                continue; // 背后不显示
            }

            // 遮挡（节流）
            boolean blocked = rayBlockedCached(world, camPos, base, distSq, e.getId(), now);
            if (blocked && !friendly) {
                continue; // 敌军：墙后直接不标（深度测试双保险）
            }

            int rgb = friendly ? FRIENDLY : HOSTILE;
            float aBase = (float) Math.max(0.40, Math.min(1.0, 1.35 - dist / MAX_DIST));
            float a = friendly && blocked ? aBase * 0.85f : aBase;

            double s = Math.max(0.24, Math.min(0.9, 0.42 + 2.6 * (1.0 - Math.min(1.0, dist / MAX_DIST))));
            boolean drew = diamondInto(buf, m, base, right, up, s * 1.5, s * 1.5, 0x000000, a * 0.55f)
                    || diamondInto(buf, m, base, right, up, s, s, rgb, a);
            wrote |= drew;
        }

        if (!wrote) {
            return; // 空 buffer 不 end/draw（否则 BufferBuilder was empty 崩溃）
        }
        BufferRenderer.drawWithGlobalProgram(buf.end());
    }

    /** 世界坐标画一个面向相机的菱形（中心 base，right/up 为相机横纵基向量）。
     *  返回是否实际写入了顶点。 */
    private static boolean diamondInto(BufferBuilder buf, Matrix4f m,
                                       Vec3d base, Vec3d right, Vec3d up,
                                       double hw, double hh, int rgb, float alpha) {
        if (alpha <= 0.01f) {
            return false;
        }
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8) & 0xFF) / 255f;
        float b = (rgb & 0xFF) / 255f;
        Vec3d top = base.add(up.multiply(hh));
        Vec3d bottom = base.subtract(up.multiply(hh));
        Vec3d left = base.subtract(right.multiply(hw));
        Vec3d rgt = base.add(right.multiply(hw));
        buf.vertex(m, (float) top.x, (float) top.y, (float) top.z).color(r, g, b, alpha);
        buf.vertex(m, (float) rgt.x, (float) rgt.y, (float) rgt.z).color(r, g, b, alpha);
        buf.vertex(m, (float) bottom.x, (float) bottom.y, (float) bottom.z).color(r, g, b, alpha);
        buf.vertex(m, (float) left.x, (float) left.y, (float) left.z).color(r, g, b, alpha);
        return true;
    }

    /** 节流版视线遮挡判定。 */
    private static boolean rayBlockedCached(ClientWorld world, Vec3d camPos, Vec3d base,
                                            double distSq, int entityId, long now) {
        RayCache c = rayCache.get(entityId);
        if (c != null && now - c.atMs() < RAY_INTERVAL_MS) {
            return c.blocked();
        }
        boolean blocked = rayBlocked(world, camPos, base, distSq);
        rayCache.put(entityId, new RayCache(blocked, now));
        return blocked;
    }

    /** 玩家(摄像机)到目标的视线是否被实心方块阻挡。 */
    private static boolean rayBlocked(ClientWorld world, Vec3d from, Vec3d to, double distSq) {
        Vec3d start = from.add(0, RAY_LIFT, 0);
        Vec3d end = to.add(0, 0.1, 0);
        var ctx = new RaycastContext(start, end,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE,
                MinecraftClient.getInstance().player);
        var hit = world.raycast(ctx);
        if (hit.getType() == HitResult.Type.MISS) {
            return false;
        }
        Vec3d hp = hit.getPos();
        return hp.squaredDistanceTo(start) < distSq * 0.96;
    }

    // ================= 阵营判定 =================

    /** 自身 side；未入队返回 -1。 */
    private static int selfSide() {
        return sideOfName(MinecraftClient.getInstance().player.getGameProfile().getName());
    }

    /**
     * 名字 → 阵营序号（0=攻 / 1=守 / -1=未知）。
     *
     * <p>优先级：位置帧名册（全量、0.25s 刷新） → 战绩榜（仅参与过击杀者） →
     * AI 命名前缀兜底。**不能只用战绩榜**：大厅阶段没有击杀，榜是空的，
     * 那样会导致自身 side 都算不出来 → 整个标记层直接不渲染。
     */
    private static int sideOfName(String name) {
        if (name == null || name.isEmpty()) {
            return -1;
        }
        for (FriendDot f : ClientMatchState.friends()) {
            if (f.name().equals(name) && f.sideOrdinal() >= 0) {
                return f.sideOrdinal();
            }
        }
        for (BoardRow r : ClientMatchState.board()) {
            if (r.name().equals(name)) {
                return r.sideOrdinal();
            }
        }
        return BotNames.sideOfBotName(name);
    }
}
