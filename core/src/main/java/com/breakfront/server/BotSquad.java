package com.breakfront.server;

import com.breakfront.game.MatchPhase;
import com.breakfront.game.Side;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 假玩家战场小队（2026-09-10 v1）—— AI BOT 重构的「可用闭环」。
 *
 * <h2>与 {@link NpcSquad} 的关系</h2>
 * 并存而非替换。{@code NpcSquad} 用 {@code ZombieEntity} 作壳、原版导航移动；
 * 本类用 {@link ServerPlayerEntity} 假玩家作壳（见 {@link BotPlayerFactory}）、
 * {@link BotMotor} 驱动位移。两者**互斥启用**：
 * <ul>
 *   <li>旧壳渲染/追踪走怪物通路，需不断打补丁（防火、禁 AI、persistent 防 despawn），
 *       仍会有「忽隐忽现」的观感问题；</li>
 *   <li>新壳与真人走**完全相同**的实体、渲染、追踪、伤害、计分通路 —— 从根上消除该问题。</li>
 * </ul>
 * 保留旧实现是为了回退安全：新系统经真机验证后再考虑摘除旧代码。
 *
 * <h2>行为（第一版：跑通优先）</h2>
 * <ul>
 *   <li><b>无敌人</b>：朝当前扇区目标据点推进（攻方聚首点、守方按 id 分散到各点）</li>
 *   <li><b>有敌人</b>（≤{@value #ENGAGE_RANGE}m 且视线通畅）：停下、转向、周期开火</li>
 *   <li><b>阵亡</b>：从名册移除，由补员逻辑在出生点重建（与真人复活语义一致）</li>
 * </ul>
 *
 * <h2>已知取舍</h2>
 * <ul>
 *   <li>假玩家入列会触发服务端「XX joined the game」广播（{@code onPlayerConnect} 内置行为）。
 *       这是管理员指令触发的运维动作，暂接受；若需静默需 mixin 拦截广播。</li>
 *   <li>寻路为「朝目标直线 + 卡住重试」，未做完整 A*。城市街区尚可，复杂室内后续升级。</li>
 * </ul>
 */
public final class BotSquad {

    /** bot 名（同时是离线 UUID 依据，须全局唯一且 ≤16 字符）。 */
    private static final String NAME_PREFIX = "BF_";
    /** 索敌半径（米）。 */
    private static final double SCAN_RANGE = 34.0;
    /** 有效射程：超出不自伤开火（与 NpcSquad 口径一致）。 */
    private static final double GUN_RANGE = 30.0;
    /** 进入交战（停步开火）的距离。 */
    private static final double ENGAGE_RANGE = 24.0;
    /** 到达判定。 */
    private static final double ARRIVE_RADIUS = 1.5;
    /** 决策间隔 / 索敌节流 / 开火间隔（tick 或 ms）。 */
    private static final int DECIDE_EVERY_TICKS = 5;
    private static final long SCAN_MS = 900;
    private static final long FIRE_MS = 500;
    /** 单次开火伤害：对齐 {@code WeaponCatalog} 的 hk416d 单发 26 伤（AR 4 发击杀）。 */
    private static final double SHOT_DAMAGE = 26.0;
    /** 卡住判定：连续该 tick 数无有效位移则重掷目标点。 */
    private static final int STUCK_TICKS = 60;
    /** 状态维持间隔（生命上限/饱食度/灭火/假连线排空）。20 tick = 1s。 */
    private static final int MAINTAIN_EVERY_TICKS = 20;

    private final Map<UUID, Trooper> troopers = new HashMap<>();
    private int targetAttacker;
    private int targetDefender;
    private int attSeq;
    private int defSeq;
    private int tickCounter;

    /** 单个 bot 的运行状态。 */
    private static final class Trooper {
        UUID id;
        String name;
        Side side;
        String cls;
        double lastX;
        double lastZ;
        double goalX;
        double goalZ;
        boolean hasGoal;
        int stalledTicks;
        long scanAtMs;
        long fireAtMs;
        /** 缓存的交战目标：每 DECIDE 节流刷新；坐标动态读取（目标会移动）。 */
        LivingTarget foe;
    }

    // ---------- 对外接口（与 NpcSquad 对齐，便于将来互换） ----------

    public void setTarget(Side side, int count) {
        if (side == Side.ATTACKER) {
            targetAttacker = Math.max(0, count);
        } else {
            targetDefender = Math.max(0, count);
        }
    }

    public int alive() {
        return troopers.size();
    }

    public String info() {
        return String.format("假玩家小队：攻 %d / 守 %d 目标，当前存活 %d",
                targetAttacker, targetDefender, troopers.size());
    }

    public void clearAll(MinecraftServer server) {
        for (Trooper t : new ArrayList<>(troopers.values())) {
            remove(server, t);
        }
        troopers.clear();
        targetAttacker = 0;
        targetDefender = 0;
    }

    /** 按目标人数补员（不改动已有单位）。 */
    public void ensure(ServerMatch match, MinecraftServer server) {
        int att = countAlive(Side.ATTACKER);
        int def = countAlive(Side.DEFENDER);
        for (int i = 0; i < Math.max(0, targetAttacker - att); i++) {
            spawn(match, server, Side.ATTACKER);
        }
        for (int i = 0; i < Math.max(0, targetDefender - def); i++) {
            spawn(match, server, Side.DEFENDER);
        }
    }

    // ---------- 每 tick 推进 ----------

    public void tick(ServerMatch match, MinecraftServer server) {
        if (troopers.isEmpty()) {
            return;
        }
        tickCounter++;
        boolean upkeep = tickCounter % MAINTAIN_EVERY_TICKS == 0;
        // 每 20 tick：清扫阵亡并补员（真人死亡也是这个节奏被系统感知）
        if (tickCounter % 20 == 0) {
            sweepAndReinforce(match, server);
        }
        if (match.game().phase() != MatchPhase.BATTLE) {
            // 非战斗阶段：不下发移动，但仍做最低限度的状态维持，
            // 否则大厅里受伤/着火的 bot 会长期残血（maintain 不回血，只做上限/饱食/灭火）。
            if (upkeep) {
                for (Trooper t : new ArrayList<>(troopers.values())) {
                    ServerPlayerEntity bot = entity(server, t);
                    if (bot != null) {
                        BotMotor.maintain(bot, 100.0);
                    }
                }
            }
            return;
        }
        long now = System.currentTimeMillis();
        boolean decide = tickCounter % DECIDE_EVERY_TICKS == 0;
        for (Trooper t : new ArrayList<>(troopers.values())) {
            ServerPlayerEntity bot = entity(server, t);
            if (bot == null) {
                continue;
            }
            if (upkeep) {
                BotMotor.maintain(bot, 100.0);
            }
            // ⚠️ 索敌（较贵）按 DECIDE 节流，但**移动每 tick 都要跑**：
            // 二者此前共用同一个 5 tick 节流，导致 bot 每 5 tick 才挪 0.25 格
            // = 1 格/秒（真人步行 4.3 格/秒），观感像幻灯片 —— 这是"bot 不动"的直接原因。
            if (decide || t.foe == null) {
                t.foe = resolveFoe(match, server, t, now);
            }
            LivingTarget foe = validFoe(t);
            if (foe != null) {
                engage(server, t, bot, foe, now);
            } else {
                advance(match, server, t, bot);
            }
        }
    }

    /** 取缓存的交战目标；已死亡/移除则弃用（下个决策 tick 会重新索敌）。 */
    private LivingTarget validFoe(Trooper t) {
        LivingTarget f = t.foe;
        if (f == null) {
            return null;
        }
        if (!f.entity.isAlive() || f.entity.isRemoved()) {
            t.foe = null;
            return null;
        }
        return f;
    }

    // ---------- 生成 / 移除 ----------

    private void spawn(ServerMatch match, MinecraftServer server, Side side) {
        ServerWorld world = server.getOverworld();
        Vec3d p = spawnPos(match, server, side);
        String cls = Kits.CLASSES[((side == Side.ATTACKER ? attSeq++ : defSeq++)) % Kits.CLASSES.length];
        // 名字须 ≤16 字符且唯一：BF_<A|D><序号>
        String name = NAME_PREFIX + (side == Side.ATTACKER ? "A" : "D")
                + (attSeq + defSeq + troopers.size());
        ServerPlayerEntity bot = BotPlayerFactory.create(server, world, name, p.x, p.y, p.z);
        if (bot == null) {
            BreakfrontServer.LOGGER.warn("[BF-Bot] 生成 {} 失败（工厂返回 null）", name);
            return;
        }
        // 游戏模式与状态：生存模式下才有正常的受伤与战斗语义
        bot.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
        // 玩家默认生命上限是 20（10 心），先抬到 100HP 体系再显式回满
        // （maintain 已不再负责回血 —— 无条件回满会让 bot 无敌，见 BotMotor.maintain 注释）
        BotMotor.maintain(bot, 100.0);
        bot.setHealth(100.0f);
        Kits.equipGun(bot, Kits.spec(cls));   // 主手挂兵种枪（玩家物品栏会同步给客户端）
        // bot 标签（breakfront.bot）由 BotPlayerFactory 统一打上，此处只补阵营标签
        bot.addCommandTag("bf.side." + (side == Side.ATTACKER ? "att" : "def"));

        Trooper t = new Trooper();
        t.id = bot.getUuid();
        t.name = name;
        t.side = side;
        t.cls = cls;
        t.lastX = bot.getX();
        t.lastZ = bot.getZ();
        troopers.put(t.id, t);
        BreakfrontServer.LOGGER.info("[BF-Bot] 生成假玩家 {}（{} / {}）存活={}",
                name, side.labelCn, cls, troopers.size());
    }

    private void remove(MinecraftServer server, Trooper t) {
        ServerPlayerEntity bot = entity(server, t);
        if (bot != null) {
            BotPlayerFactory.remove(server, bot);
        }
        troopers.remove(t.id);
    }

    /** 清扫阵亡 bot 并补齐目标人数。 */
    private void sweepAndReinforce(ServerMatch match, MinecraftServer server) {
        boolean changed = false;
        for (Trooper t : new ArrayList<>(troopers.values())) {
            ServerPlayerEntity bot = entity(server, t);
            if (bot == null || !bot.isAlive()) {
                // 玩家实体死亡后仍存在于列表（等待重生），必须显式摘除，否则会"幽灵在线"
                if (bot != null) {
                    BotPlayerFactory.remove(server, bot);
                }
                troopers.remove(t.id);
                changed = true;
            }
        }
        if (changed) {
            ensure(match, server);
        }
    }

    // ---------- 行为 ----------

    /** 无敌人：朝目标据点推进。 */
    private void advance(ServerMatch match, MinecraftServer server, Trooper t, ServerPlayerEntity bot) {
        if (!t.hasGoal || arrived(bot, t)) {
            Vec3d goal = goalFor(match, server, t);
            if (goal == null) {
                return;
            }
            t.goalX = goal.x;
            t.goalZ = goal.z;
            t.hasGoal = true;
        }
        // 卡住检测：位移过小则重掷目标（换站位偏移，绕开局部障碍）
        double moved = Math.hypot(bot.getX() - t.lastX, bot.getZ() - t.lastZ);
        t.stalledTicks = moved < 0.05 ? t.stalledTicks + 1 : 0;
        t.lastX = bot.getX();
        t.lastZ = bot.getZ();
        if (t.stalledTicks >= STUCK_TICKS) {
            t.hasGoal = false;
            t.stalledTicks = 0;
            return;
        }
        BotMotor.stepToward(bot, t.goalX, t.goalZ, BotMotor.DEFAULT_SPEED);
    }

    private boolean arrived(ServerPlayerEntity bot, Trooper t) {
        return BotMotor.distXZ(bot, t.goalX, t.goalZ) <= ARRIVE_RADIUS;
    }

    /** 交战：超出交战距离则逼近，进入则停步、转向、周期开火。 */
    private void engage(MinecraftServer server, Trooper t, ServerPlayerEntity bot,
                        LivingTarget foe, long now) {
        double dist = BotMotor.distXZ(bot, foe.x(), foe.z());
        BotMotor.faceTo(bot, foe.x(), foe.z());
        if (dist > ENGAGE_RANGE) {
            // 逼近途中保持不开火（先进入有效射程，避免"隔街互射"观感）
            BotMotor.stepToward(bot, foe.x(), foe.z(), BotMotor.DEFAULT_SPEED);
            return;
        }
        if (now < t.fireAtMs) {
            return;
        }
        t.fireAtMs = now + FIRE_MS;
        if (dist > GUN_RANGE || !lineOfSight(bot, foe)) {
            return;
        }
        bot.swingHand(net.minecraft.util.Hand.MAIN_HAND);   // 挥臂动画（客户端可见）
        foe.entity.damage(bot.getDamageSources().playerAttack(bot), (float) SHOT_DAMAGE);
    }

    // ---------- 索敌 ----------

    /** 目标抽象：真人玩家与敌方 bot 统一成「有坐标、可受伤」。 */
    private static final class LivingTarget {
        final net.minecraft.entity.LivingEntity entity;
        final boolean isBot;

        LivingTarget(net.minecraft.entity.LivingEntity e, boolean isBot) {
            this.entity = e;
            this.isBot = isBot;
        }

        /**
         * 坐标**动态读取**而非构造时快照：目标每 tick 都在移动，快照会让
         * 每 tick 驱动的移动/瞄准一直追着旧位置（尤其在 engage 改为每 tick 调用之后）。
         */
        double x() {
            return entity.getX();
        }

        double z() {
            return entity.getZ();
        }
    }

    /** 索敌：取最近敌方（真人按阵营匹配；敌方 bot 从本队名册取）。无则 null。 */
    private LivingTarget resolveFoe(ServerMatch match, MinecraftServer server, Trooper t, long now) {
        LivingTarget cached = t.foe;
        if (cached != null && cached.entity.isAlive() && !cached.entity.isRemoved()) {
            return cached;                    // 沿用上一轮目标，避免来回切换
        }
        if (now < t.scanAtMs) {
            return null;                      // 扫描节流窗口内不做全量搜索
        }
        t.scanAtMs = now + SCAN_MS;

        ServerPlayerEntity self = entity(server, t);
        if (self == null) {
            return null;
        }
        double sx = self.getX();
        double sz = self.getZ();
        LivingTarget best = null;
        double bestD = SCAN_RANGE * SCAN_RANGE;

        // 真人玩家
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (isBotPlayer(p)) {
                continue;                     // bot 在下面的集合里单独处理（避免重复并识别来源）
            }
            Side s = match.teams().sideOf(p.getUuid());
            if (s == null || s == t.side) {
                continue;
            }
            double d = sq(sx, sz, p.getX(), p.getZ());
            if (d < bestD) {
                bestD = d;
                best = new LivingTarget(p, false);
            }
        }
        // 敌方 bot
        for (Trooper o : troopers.values()) {
            if (o == t || o.side == t.side) {
                continue;
            }
            ServerPlayerEntity e = entity(server, o);
            if (e == null || !e.isAlive()) {
                continue;
            }
            double d = sq(sx, sz, e.getX(), e.getZ());
            if (d < bestD) {
                bestD = d;
                best = new LivingTarget(e, true);
            }
        }
        return best;
    }

    private boolean isBotPlayer(ServerPlayerEntity p) {
        // 用统一标签判定而非本小队登记表：这样其它小队/残留的 bot 也能被识别，
        // 不会在「扫描真人」时被错误地当成真人重复计入。
        return BotPlayerFactory.isBot(p);
    }

    private boolean lineOfSight(ServerPlayerEntity bot, LivingTarget foe) {
        Vec3d from = bot.getEyePos();
        Vec3d to = foe.entity.getEyePos();
        var ctx = new net.minecraft.world.RaycastContext(from, to,
                net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                net.minecraft.world.RaycastContext.FluidHandling.NONE, bot);
        var hit = bot.getServerWorld().raycast(ctx);
        if (hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS) {
            return true;
        }
        double d = Math.sqrt(sq(bot.getX(), bot.getZ(), foe.x(), foe.z()));
        return hit.getPos().squaredDistanceTo(from) > d * d * 0.96;
    }

    // ---------- 目标点 / 出生点 ----------

    /** 目标点：攻方取当前扇区首个据点中心；守方按 id 分散到各据点。 */
    private Vec3d goalFor(ServerMatch match, MinecraftServer server, Trooper t) {
        var sector = match.game().currentSector();
        if (sector == null) {
            return null;                     // 无当前扇区（大厅/回合间隙）
        }
        List<com.breakfront.game.ZoneState> zones = sector.zones();
        if (zones.isEmpty()) {
            return null;
        }
        int pick = t.side == Side.ATTACKER ? 0 : (t.id.hashCode() & 0x7fffffff) % zones.size();
        int idx = match.zoneIndex(zones.get(pick).id());
        double[] c = match.zoneCenter(idx);
        if (c == null) {
            return null;
        }
        double r = Math.max(2.0, match.zoneRadius(idx));
        long h = t.id.hashCode() & 0x7fffffffL;
        double ox = ((h % 1001) / 1000.0 - 0.5) * 1.5 * r;
        double oz = (((h >> 16) % 1001) / 1000.0 - 0.5) * 1.5 * r;
        double tx = c[0] + ox;
        double tz = c[1] + oz;
        return new Vec3d(tx, BotMotor.surfaceY(server.getOverworld(), tx, tz), tz);
    }

    /** 出生点：以阵营出生区为中心做环带散布，并贴合地表。 */
    private Vec3d spawnPos(ServerMatch match, MinecraftServer server, Side side) {
        double[] s = match.spawnsFor(side, server.getOverworld());
        ServerWorld world = server.getOverworld();
        double baseY = s[1];
        for (int attempt = 0; attempt < 10; attempt++) {
            double ang = Math.random() * Math.PI * 2.0;
            double rad = 2.0 + Math.random() * 18.0;
            double tx = s[0] + Math.cos(ang) * rad;
            double tz = s[2] + Math.sin(ang) * rad;
            double g = BotMotor.surfaceY(world, tx, tz);
            if (Math.abs(g - baseY) <= 5.0 && g > world.getBottomY()) {
                return new Vec3d(tx, g, tz);
            }
        }
        return new Vec3d(s[0], BotMotor.surfaceY(world, s[0], s[2]), s[2]);
    }

    // ---------- 工具 ----------

    private ServerPlayerEntity entity(MinecraftServer server, Trooper t) {
        return server.getPlayerManager().getPlayer(t.id);
    }

    private int countAlive(Side side) {
        int n = 0;
        for (Trooper t : troopers.values()) {
            if (t.side == side) {
                n++;
            }
        }
        return n;
    }

    private static double sq(double ax, double az, double bx, double bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return dx * dx + dz * dz;
    }

    /** 兵种展示名（供日志/管理台）。 */
    public String roster() {
        StringBuilder sb = new StringBuilder();
        for (Trooper t : troopers.values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(t.name).append('(').append(t.side == Side.ATTACKER ? "攻" : "守")
                    .append('/').append(t.cls).append(')');
        }
        return sb.toString();
    }
}
