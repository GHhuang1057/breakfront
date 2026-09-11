package com.breakfront.server;

import net.minecraft.block.BlockState;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

/**
 * 假玩家「运动与生存维持」驱动（2026-09-10 v1）。
 *
 * <h2>为什么需要它</h2>
 * v0.5–v0.7 的 bot 用 {@code ZombieEntity}（{@code MobEntity} 子类），位移直接交给原版
 * {@code getNavigation()} 寻路 —— 省事，但僵尸壳带来渲染与行为上的一系列副作用。
 * 改用 {@link ServerPlayerEntity} 假玩家后，**没有** {@code getNavigation()}：
 * 玩家实体的位移依赖客户端输入（C2S 输入包），而假玩家没有真实客户端。
 *
 * <h2>位移方案：服务端权威贴地滑行</h2>
 * 三条路线中择优：
 * <ol>
 *   <li><b>setVelocity</b> —— 不可行：{@code PlayerEntity.tickMovement()} 每 tick 会用
 *       input 驱动的 {@code updateVelocity()} 覆盖水平速度，外部设置的速度立刻被冲掉。</li>
 *   <li><b>teleport 每 tick</b> —— 会走玩家位置包通路，位移大时被客户端当作瞬移，
 *       观感抖动（正是要避免的老问题）。</li>
 *   <li><b>setPos 小步推进</b>（本类采用）—— 每 tick 位移 ≤ 一个步行步长，
 *       实体追踪按增量包同步给周围真人玩家，客户端自行插值 → 平顺。
 *       同时归零速度消除重力/惯性干扰，并把 y 贴到地表。</li>
 * </ol>
 *
 * <p>真人客户端看到的假玩家由服务端权威位置驱动，因此外观与真实玩家完全一致
 * （同一个 {@code ServerPlayerEntity} 渲染通路）—— 这是根治「忽隐忽现」的关键。
 */
public final class BotMotor {

    /** 到达判定半径（米）：水平距离小于它即视为抵达目标。 */
    private static final double ARRIVE_RADIUS = 1.5;
    /** 贴地偏移：略高于地表，避免脚陷进方块导致碰撞推挤。 */
    private static final double SURFACE_OFFSET = 0.1;
    /** 单 tick 最大水平位移（格子）。0.25 ≈ 5 格/秒，介于步行(4.3)与冲刺(5.6)之间。 */
    public static final double DEFAULT_SPEED = 0.25;
    /** 需要跨台阶时的抬升阈值：目标点比当前高不超过此值则直接踏上。 */
    private static final double STEP_UP = 1.25;
    /** 邻域地表采样半径（找不到直接落点时向外找）。 */
    private static final int SURFACE_PROBE = 6;
    /** 向下寻支撑面的最大落差（格）：超过视为悬空/掉出地图，交还 NaN 由上层兜底。 */
    private static final int FLOOR_DROP = 10;

    private BotMotor() {
    }

    /**
     * 朝目标点水平推进一步（贴地滑行）。到达返回 true。
     *
     * @param bot           假玩家
     * @param tx,tz         目标水平坐标
     * @param speedPerTick  本 tick 最大位移（格子）；建议 {@link #DEFAULT_SPEED}
     * @return true = 已进入到达半径
     */
    public static boolean stepToward(ServerPlayerEntity bot, double tx, double tz, double speedPerTick) {
        double dx = tx - bot.getX();
        double dz = tz - bot.getZ();
        double dist = Math.hypot(dx, dz);
        if (dist <= ARRIVE_RADIUS) {
            faceTo(bot, tx, tz);
            return true;
        }
        double step = Math.min(Math.max(0.01, speedPerTick), dist);
        double nx = bot.getX() + dx / dist * step;
        double nz = bot.getZ() + dz / dist * step;

        ServerWorld world = bot.getServerWorld();
        // 2026-09-11 修复「穿模」：改取**脚下当前层**的支撑面，而非整列最高表面。
        // 此前 surfaceY 取 WORLD_SURFACE 列顶——地铁/多层城市图里列顶是屋顶，
        // bot 会被吸到屋顶再setPos，视觉即穿模/瞬移。现从脚下高度向下找第一块实心方块作地面。
        double ny = floorYUnder(world, nx, nz, bot.getY());
        if (Double.isNaN(ny)) {
            ny = surfaceY(world, nx, nz); // 兜底：脚下无地面（悬空）则退守列表面
        }
        // 台阶保护：目标点明显高于当前（且超出可踏高度）时，不硬推上去，交由上层绕行
        if (ny - bot.getY() > STEP_UP) {
            ny = bot.getY();
        }

        // 墙体碰撞避让：目标格被实心方块占据则尝试单侧滑步绕行，两侧都堵则原地等待
        // （advance() 的卡住检测会在 STUCK_TICKS 后重选目标点，避免永久卡死）。
        if (isBlocked(world, nx, nz, ny)) {
            boolean xOk = !isBlocked(world, nx, bot.getZ(), ny);
            boolean zOk = !isBlocked(world, bot.getX(), nz, ny);
            if (xOk && zOk) {
                // 都能走：优先沿更接近目标的一侧推进
                if (Math.abs(dx) >= Math.abs(dz)) {
                    nz = bot.getZ();
                } else {
                    nx = bot.getX();
                }
            } else if (xOk) {
                nz = bot.getZ();
            } else if (zOk) {
                nx = bot.getX();
            } else {
                faceTo(bot, tx, tz);
                return false; // 完全堵死：不动，等上层重选
            }
        }

        faceTo(bot, tx, tz);
        bot.setPos(nx, ny, nz);
        // 归零速度：消除重力的逐 tick 累积与惯性，保证位移完全由本类决定。
        // （不必置 velocityModified —— 真人客户端对假玩家只做位置插值，不做物理预测）
        bot.setVelocity(Vec3d.ZERO);
        return false;
    }

    /** 朝目标水平方向转向（同时刷新 yaw / headYaw / bodyYaw，避免头部与身体脱节）。 */
    public static void faceTo(ServerPlayerEntity bot, double tx, double tz) {
        double dx = tx - bot.getX();
        double dz = tz - bot.getZ();
        if (dx == 0 && dz == 0) {
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(dx, dz));
        bot.setYaw(yaw);
        bot.setHeadYaw(yaw);
        bot.setBodyYaw(yaw);
    }

    /**
     * 维持假玩家的战斗可用状态。建议每 20 tick 调用一次（不必每 tick）。
     *
     * <p>处理玩家实体特有、会被环境拖垮的几项：
     * <ul>
     *   <li><b>生命上限</b>：玩家默认 20（10 心），需按 BREAKFRONT 的 100HP 体系抬到
     *       {@code maxHealth}。⚠️ 本方法**不回血** —— 此前无条件回满会让 bot 实际无敌
     *       （每 5 tick 抹掉一切伤害），战斗完全失效。满血只在出生时设置（见 BotSquad.spawn）。</li>
     *   <li><b>饱食度</b>：饥饿会掉血且阻止自然回血（naturalRegeneration 已关）—— 持续补满</li>
     *   <li><b>着火</b>：玩家不会像僵尸那样自燃，但可能被地图火源/爆炸点燃，统一熄灭</li>
     *   <li><b>假连线排空</b>：丢弃 EmbeddedChannel 堆积的出站包，防内存泄漏</li>
     * </ul>
     */
    public static void maintain(ServerPlayerEntity bot, double maxHealth) {
        var attr = bot.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        if (attr != null && attr.getBaseValue() != maxHealth) {
            attr.setBaseValue(maxHealth);
        }
        var hunger = bot.getHungerManager();
        hunger.setFoodLevel(20);
        hunger.setSaturationLevel(5.0f);
        if (bot.isOnFire()) {
            bot.setFireTicks(0);
        }
        BotPlayerFactory.drainOutbound(bot);
    }

    /**
     * 采样 (x,z) 处可站立的地表高度。直接列无方块时向四周探测（低海拔/虚空地图必需 ——
     * 否则会回退到世界顶导致 bot 悬空）。
     */
    public static double surfaceY(ServerWorld world, double x, double z) {
        int fx = (int) Math.floor(x);
        int fz = (int) Math.floor(z);
        int top = world.getTopY(Heightmap.Type.WORLD_SURFACE, fx, fz);
        if (top > world.getBottomY()) {
            return top + SURFACE_OFFSET;
        }
        double best = Double.NaN;
        for (int dx = -SURFACE_PROBE; dx <= SURFACE_PROBE; dx++) {
            for (int dz = -SURFACE_PROBE; dz <= SURFACE_PROBE; dz++) {
                int t = world.getTopY(Heightmap.Type.WORLD_SURFACE, fx + dx, fz + dz);
                if (t > world.getBottomY() && (Double.isNaN(best) || t > best)) {
                    best = t;
                }
            }
        }
        return Double.isNaN(best) ? world.getBottomY() + 3 : best + SURFACE_OFFSET;
    }

    /**
     * 采样 (x,z) 处<b>脚下当前层</b>的支撑面高度（多层级地图防穿模核心）。
     *
     * <p>从 bot 当前脚下方开始向下扫描，返回第一个实心方块顶面 + 贴地偏移，
     * 使 bot 始终贴着自己所在楼层走动，而不是被 {@link #surfaceY} 吸到列顶（屋顶）。
     * 落差超过 {@link #FLOOR_DROP} 视为悬空，返回 {@code NaN} 交上层兜底。
     */
    public static double floorYUnder(ServerWorld world, double x, double z, double currentY) {
        int fx = (int) Math.floor(x);
        int fz = (int) Math.floor(z);
        int start = (int) Math.floor(currentY) - 1; // 从脚下方一格开始向下找支撑
        int bottom = (int) Math.floor(currentY) - FLOOR_DROP;
        for (int y = start; y >= bottom; y--) {
            if (y < world.getBottomY()) {
                break;
            }
            BlockPos bp = new BlockPos(fx, y, fz);
            BlockState bs = world.getBlockState(bp);
            if (bs.isSolid()) {
                return y + 1 + SURFACE_OFFSET; // 站在这块方块上面
            }
        }
        return Double.NaN;
    }

    /** (x,z) 处、以 footY 为脚底高度的身体竖列是否被实心方块占据（墙体/柱体检测）。 */
    private static boolean isBlocked(ServerWorld world, double x, double z, double footY) {
        int fx = (int) Math.floor(x);
        int fz = (int) Math.floor(z);
        int y0 = (int) Math.floor(footY);   // 站立格
        int y1 = y0 + 1;                    // 躯干
        int y2 = y0 + 2;                    // 头部（玩家身高 ~1.8）
        return solidAt(world, fx, y0, fz) || solidAt(world, fx, y1, fz) || solidAt(world, fx, y2, fz);
    }

    private static boolean solidAt(ServerWorld world, int x, int y, int z) {
        if (y < world.getBottomY()) {
            return false;
        }
        return world.getBlockState(new BlockPos(x, y, z)).isSolid();
    }

    /** 水平距离（米）。 */
    public static double distXZ(ServerPlayerEntity bot, double tx, double tz) {
        return Math.hypot(tx - bot.getX(), tz - bot.getZ());
    }
}
