package com.breakfront.server;

import com.breakfront.game.BreakthroughTuning;
import com.breakfront.game.MatchPhase;
import com.breakfront.game.Side;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.SwordItem;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 增援单位（NPC bot）v0.5 —— 服务端实体方案。
 *
 * 行为模型：
 * - 进服补位：缺人一方按目标人数自动补 bot（多人人数不够时自动加 bot）
 * - 推进：攻方 bot 前往当前扇区首个目标点站圈（提供占点人数）；守方 bot 分散守当前扇区各点（争夺）
 * - 每局重开时清场重部署到己方出生区
 * 说明：v0.7 起 移动改走原版实体导航 AI（PathAwareEntity / MobEntity.getNavigation），
 * 由本类每 ~5 tick 决策「走哪」，仅射击/换目标/近战由本模块直接驱动；/tp 仅保留
 * 在 出生部署 / 救援 / 卡死兜底，消除原先 per-step /tp 造成的瞬移与视觉碎裂。
 */
public final class NpcSquad {

    private static final String TAG_PREFIX = "bf_npc_";

    private final Map<UUID, Npc> units = new HashMap<>();
    private int targetAttacker;
    private int targetDefender;
    private int stepCounter;
    /** 各阵营 bot 生成序号（用于兵种轮转分配）。 */
    private int attSeq;
    private int defSeq;

    private static final class Npc {
        Side side;
        String shortId;
        UUID id;
        String cls;    // 兵种：assault/engineer/support/recon
        double lastX;
        double lastY;
        double lastZ;
        // 交战状态（2026-09-05 v0.6）
        long scanAtMs;      // 下次目标扫描时间
        UUID foeId;         // 当前敌人（null=无）
        boolean foeIsNpc;   // 敌人是 NPC（true）还是真人玩家（false）
        long atkAtMs;       // 下次可攻击时间
        int armTries;       // 挂枪尝试计数（selector 未命中重试上限）
        long lastArmAtMs;   // 上次挂枪尝试时间
        // 导航 / 卡死兜底（2026-09-06 v0.7：原版导航 AI 取代 /tp 推进）
        double goalX;       // 当前导航目标 x
        double goalY;       // 当前导航目标 y
        double goalZ;       // 当前导航目标 z
        long stuckSinceMs;  // 卡死监测窗口起点（0=未开始计时）
        double prevMoveX;   // 上一决策帧水平位置（卡死位移判定）
        double prevMoveZ;
    }

    // ---------- 配置 ----------

    public void setTarget(Side side, int count) {
        if (side == Side.ATTACKER) {
            targetAttacker = Math.max(0, count);
        } else {
            targetDefender = Math.max(0, count);
        }
    }

    public int alive() {
        return units.size();
    }

    public String info() {
        return String.format("NPC 目标 攻 %d / 守 %d，当前存活 %d", targetAttacker, targetDefender, units.size());
    }

    public void clearAll(MinecraftServer server) {
        killAll(server);
        targetAttacker = 0;
        targetDefender = 0;
    }

    // ---------- 回合衔接 ----------

    public void beginRound(ServerMatch match, MinecraftServer server) {
        killAll(server);
        ensure(match, server);
    }

    /** 按目标人数即时补员（不改动已有单位）。 */
    public void topUp(ServerMatch match, MinecraftServer server) {
        ensure(match, server);
    }

    private void ensure(ServerMatch match, MinecraftServer server) {
        int onlineAtt = online(match, server, Side.ATTACKER);
        int onlineDef = online(match, server, Side.DEFENDER);
        for (int i = 0; i < Math.max(0, targetAttacker - onlineAtt); i++) {
            spawn(match, server, Side.ATTACKER);
        }
        for (int i = 0; i < Math.max(0, targetDefender - onlineDef); i++) {
            spawn(match, server, Side.DEFENDER);
        }
        redeployAll(match, server);
        forceLoadAll(server);
    }

    /**
     * 按两侧目标总人数（真人 + bot）填充并裁剪 —— 供「按机器负载动态补员」调用。
     * 目标调低时先裁剪再补员，目标调高时只补不裁。
     */
    public void applyFill(ServerMatch match, MinecraftServer server, int attTotal, int defTotal) {
        setTarget(Side.ATTACKER, attTotal);
        setTarget(Side.DEFENDER, defTotal);
        ensure(match, server);
        trimToTarget(match, server);
    }

    private void trimToTarget(ServerMatch match, MinecraftServer server) {
        int wantAtt = Math.max(0, targetAttacker - online(match, server, Side.ATTACKER));
        int wantDef = Math.max(0, targetDefender - online(match, server, Side.DEFENDER));
        killExcess(server, Side.ATTACKER, wantAtt);
        killExcess(server, Side.DEFENDER, wantDef);
    }

    /** 战斗中补员（每 3s）：两侧 bot 目标 = target - 该侧在线真人；每 3s 每边最多补 8，
     *  快速回满编制，让玩家始终看到成建制的 AI 对抗（无真人也维持演示）。 */
    private void reinforce(ServerMatch match, MinecraftServer server) {
        int wa = targetAttacker - online(match, server, Side.ATTACKER);
        int wd = targetDefender - online(match, server, Side.DEFENDER);
        int spawned = 0;
        int na = Math.min(Math.max(0, wa), 8);
        for (int i = 0; i < na; i++) {
            spawn(match, server, Side.ATTACKER);
            spawned++;
        }
        int nd = Math.min(Math.max(0, wd), 8);
        for (int i = 0; i < nd; i++) {
            spawn(match, server, Side.DEFENDER);
            spawned++;
        }
        if (spawned > 0) {
            forceLoadAll(server);
            BreakfrontServer.LOGGER.info("[Breakfront] battle reinforce +{} (att {}/{} def {}/{})",
                    spawned, targetAttacker, online(match, server, Side.ATTACKER),
                    targetDefender, online(match, server, Side.DEFENDER));
        }
    }

    private void killExcess(MinecraftServer server, Side side, int want) {
        List<UUID> candidates = new ArrayList<>();
        for (Npc n : units.values()) {
            if (n.side == side) {
                candidates.add(n.id);
            }
        }
        int excess = candidates.size() - want;
        if (excess <= 0) {
            return;
        }
        for (UUID id : candidates) {
            if (excess <= 0) {
                break;
            }
            Npc n = units.remove(id);
            if (n != null) {
                exec(server, "kill @e[tag=" + TAG_PREFIX + n.shortId + "]");
                excess--;
            }
        }
    }

    /** 无玩家时区块不常驻 → NPC 落到未加载区块；对 NPC 所在区块开 forceload。 */
    private void forceLoadAll(MinecraftServer server) {
        for (Npc n : units.values()) {
            exec(server, String.format("forceload add %d %d",
                    (int) Math.floor(n.lastX) >> 4, (int) Math.floor(n.lastZ) >> 4));
        }
    }

    /** 清扫已死亡/消失的单位（防止对空实体持续发 tp 导致控制台刷屏）。 */
    public void sweep(MinecraftServer server) {
        if (units.isEmpty()) {
            return;
        }
        var world = server.getOverworld();
        var it = units.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (world.getEntity(entry.getKey()) == null) {
                it.remove();
            }
        }
    }

    private int online(ServerMatch match, MinecraftServer server, Side side) {
        int n = 0;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (match.teams().sideOf(p.getUuid()) == side) {
                n++;
            }
        }
        return n;
    }

    private void killAll(MinecraftServer server) {
        for (Npc n : new ArrayList<>(units.values())) {
            exec(server, "kill @e[tag=" + TAG_PREFIX + n.shortId + "]");
        }
        units.clear();
    }

    private void redeployAll(ServerMatch match, MinecraftServer server) {
        for (Npc n : units.values()) {
            Vec3d p = spawnPos(match, server, n.side);
            n.lastX = p.x;
            n.lastY = p.y;
            n.lastZ = p.z;
            exec(server, tpCmd(n, p.x, p.y, p.z, 0));
        }
    }

    // ---------- 生成 ----------

    private void spawn(ServerMatch match, MinecraftServer server, Side side) {
        ServerWorld world = server.getOverworld();
        Vec3d p = spawnPos(match, server, side);
        ZombieEntity e = new ZombieEntity(EntityType.ZOMBIE, world);
        String sid = UUID.randomUUID().toString().substring(0, 8);
        // 兵种轮转分配（双方同序循环，保证 4 兵种都有）
        String cls = Kits.CLASSES[((side == Side.ATTACKER ? attSeq++ : defSeq++))
                % Kits.CLASSES.length];
        String label = side == Side.ATTACKER ? "攻方增援" : "守方增援";
        e.setCustomName(Text.literal("[" + label + "] AI-" + sid));
        // 名字牌不显示：头顶阵营标记由客户端 FriendlyHostileMarks 接管（蓝=友/红=敌，穿墙语义不同）
        e.setCustomNameVisible(false);
        e.setAiDisabled(false);     // v0.7：启用原版导航 AI，移动交给 getNavigation()
        e.setNoGravity(false);      // 让导航按地形行走（落体/爬台阶正常）
        e.setSilent(true);          // 不出僵尸声（配合客户端去原版音效）
        e.addCommandTag("breakfront.npc");
        e.addCommandTag("bf.side." + (side == Side.ATTACKER ? "att" : "def"));
        e.addCommandTag("bf.cls." + cls);
        e.addCommandTag(TAG_PREFIX + sid);
        e.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MAX_HEALTH)
                .setBaseValue(BreakthroughTuning.PLAYER_MAX_HEALTH);
        e.setHealth((float) BreakthroughTuning.PLAYER_MAX_HEALTH);
        // zombie 白天自燃/地形挤压防护：长效防火+再生（仍可被击杀）
        e.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                net.minecraft.entity.effect.StatusEffects.FIRE_RESISTANCE, 240000, 0, false, false));
        e.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                net.minecraft.entity.effect.StatusEffects.REGENERATION, 240000, 1, false, false));
        // 兵种主武器挂主手（客户端 BotSoldierRenderer 会画在史蒂夫士兵手上）。
        // 必须在实体入世后执行（selector @e 才命中），首帧未中就由 tick 自动重试补挂。
        Kits.KitSpec kit = Kits.spec(cls);
        e.setPosition(p.x, p.y, p.z);
        world.spawnEntity(e);
        armNpc(server, e, kit);

        Npc n = new Npc();
        n.side = side;
        n.shortId = sid;
        n.id = e.getUuid();
        n.cls = cls;
        n.lastX = p.x;
        n.lastY = p.y;
        n.lastZ = p.z;
        n.armTries = 1;
        n.lastArmAtMs = System.currentTimeMillis();
        units.put(n.id, n);
        BreakfrontServer.LOGGER.info("[Breakfront] npc {} spawned ({} / {} , {}) alive={}",
                label + " AI-" + sid, side.labelCn, cls, kit.gunId(), units.size());
    }

    /** 给指定 NPC 主手挂 TaCZ 枪（replaceitem 用唯一 tag 选择；入世后再调）。 */
    private static void armNpc(MinecraftServer server, net.minecraft.entity.LivingEntity e, Kits.KitSpec kit) {
        String sid = null;
        for (String tag : e.getCommandTags()) {
            if (tag.startsWith(TAG_PREFIX)) {
                sid = tag.substring(TAG_PREFIX.length());
                break;
            }
        }
        if (sid == null) {
            return;
        }
        exec(server, String.format(
                "replaceitem entity @e[tag=%s%s,limit=1] weapon.mainhand "
                        + "tacz:modern_kinetic_gun{GunId:\"tacz:%s\",GunCurrentAmmoCount:%d} 1",
                TAG_PREFIX, sid, kit.gunId(), kit.magSize()));
    }

    /** 出生点：以阵营出生区中心为基准做 ±9m 随机散布，避免整队叠单点；
     *  散布点与基准地表高度差过大时回退中心（防止卡进墙/悬空）。 */
    private static final java.util.Random SPAWN_RNG = new java.util.Random();

    private Vec3d spawnPos(ServerMatch match, MinecraftServer server, Side side) {
        double[] s = match.spawnsFor(side, server.getOverworld());
        double baseY = s[1];
        for (int attempt = 0; attempt < 10; attempt++) {
            double ang = SPAWN_RNG.nextDouble() * Math.PI * 2.0;
            // 环带 2..20m 散布（低海拔地图地面可为负值：阈值为世界底，勿用 >0 判定）
            double rad = 2.0 + SPAWN_RNG.nextDouble() * 18.0;
            double tx = s[0] + Math.cos(ang) * rad;
            double tz = s[2] + Math.sin(ang) * rad;
            double g = groundY(server, tx, tz);
            if (Math.abs(g - baseY) <= 5.0 && g > -66.0) {
                return new Vec3d(tx, g + 0.3, tz);
            }
        }
        // 全失败：以中心向 +x 找可站柱（防叠点）
        for (int d = 4; d <= 40; d += 4) {
            double g = groundY(server, s[0] + d, s[2]);
            if (g > -66.0) {
                return new Vec3d(s[0] + d, g + 0.3, s[2]);
            }
        }
        return new Vec3d(s[0], baseY, s[2]);
    }

    // ---------- 每 tick 推进（v0.6：占点 + 寻敌交战） ----------

    /** 交战距离（米）：空手近战 <4；持械 ≤30。 */
    private static final double MELEE_RANGE = 3.4;
    private static final double GUN_RANGE = 30.0;
    private static final double SCAN_RANGE = 34.0;
    /** 进入交战（停下导航、原地开火）的触发距离：持械约 24m、空手约 3m。 */
    private static final double GUN_ENGAGE = 24.0;
    private static final double MELEE_ENGAGE = 3.0;
    /** 导航移动速度（传给 startMovingTo 的速度系数，约 0.9-1.1）。 */
    private static final double MOVE_SPEED = 1.0;
    /** 占点到达判定（水平 ≤1.5m 即停导航）。 */
    private static final double ARRIVE_RADIUS = 1.5;
    /** 卡死兜底：水平位移窗口 4s 内 <0.5m 且距目标 >8m → 一次 /tp。 */
    private static final long STUCK_MS = 4000;
    private static final double STUCK_MOVE = 0.5;
    private static final double STUCK_GOAL = 8.0;
    /** 目标重扫间隔 / 攻击间隔（ms）。 */
    private static final long SCAN_MS = 900;
    private static final long MELEE_ATK_MS = 700;
    private static final long GUN_ATK_MS = 900;

    public void tick(ServerMatch match, MinecraftServer server) {
        // 非战斗阶段：bot 原地待命（清目标 + 停导航 + 站桩），不跑攻击/移动策略
        if (match.game().phase() != MatchPhase.BATTLE) {
            idleAll(server);
            return;
        }
        stepCounter++;
        if (stepCounter % 20 == 0) {
            sweep(server); // 每秒清扫阵亡单位（顺带消灭「No entity was found」刷屏）
        }
        if (stepCounter % 60 == 0) {
            reinforce(match, server); // 每 3s 快速补员：AI 减员后战场快速回满，避免"一闪而过"
        }
        if (units.isEmpty()) {
            return;
        }
        if (stepCounter % 5 != 0) { // v0.7：每 5 tick（0.25s）决策一步，导航自带平顺插值与朝向
            return;
        }
        long now = System.currentTimeMillis();
        for (Npc n : units.values()) {
            // 取真实实体并同步记录坐标（导航下位置由引擎推进，不再由我们 /tp 维护）
            var e = server.getOverworld().getEntity(n.id);
            if (!(e instanceof LivingEntity le)) {
                continue;
            }
            n.lastX = le.getX();
            n.lastY = le.getY();
            n.lastZ = le.getZ();
            // 抑制原版僵尸自带的追击/挥击（保持只走我们的策略）：清掉它的目标选择器结果
            if (e instanceof MobEntity mob) {
                mob.setTarget(null);
            }
            // 补挂枪兜底：selector 首帧未命中/被清空等导致主手空 → 每 ≥1.5s 重试（上限 6）
            if (n.armTries < 6 && le.getMainHandStack().isEmpty()
                    && now - n.lastArmAtMs > 1500) {
                armNpc(server, le, Kits.spec(n.cls));
                n.armTries++;
                n.lastArmAtMs = now;
            }
            LivingEntity foe = resolveFoe(match, server, n, now);
            if (foe != null) {
                engage(match, server, n, (MobEntity) e, foe, now);
            } else {
                moveToObjective(match, server, n, (MobEntity) e);
            }
            // 卡死兜底（水平位移过久且离目标远 → 一次 /tp 到目标附近安全点）
            if (e instanceof MobEntity mob) {
                stuckCheck(server, n, mob, now);
            }
        }
    }

    /** 当前敌人是否仍然有效；空则按节流间隔重新扫描。 */
    private LivingEntity resolveFoe(ServerMatch match, MinecraftServer server, Npc n, long now) {
        LivingEntity cached = fetchFoe(server, n);
        if (cached != null && cached.isAlive() && isFoe(match, n, cached)) {
            return cached;
        }
        if (now < n.scanAtMs) {
            return null;
        }
        n.scanAtMs = now + SCAN_MS;
        LivingEntity foe = findFoe(match, server, n, now);
        n.foeId = null;
        n.foeIsNpc = false;
        if (foe != null) {
            if (foe instanceof ServerPlayerEntity) {
                n.foeId = foe.getUuid();
                n.foeIsNpc = false;
            } else {
                n.foeId = foe.getUuid();
                n.foeIsNpc = true;
            }
        }
        return foe;
    }

    /** 依据缓存 ID 找回敌人实体。 */
    private LivingEntity fetchFoe(MinecraftServer server, Npc n) {
        if (n.foeId == null) {
            return null;
        }
        if (n.foeIsNpc) {
            var w = server.getOverworld();
            var e = w.getEntity(n.foeId);
            return e instanceof LivingEntity le ? le : null;
        }
        var p = server.getPlayerManager().getPlayer(n.foeId);
        return p;
    }

    /** 阵营敌意判定（玩家查 TeamManager；NPC 读记录）。 */
    private boolean isFoe(ServerMatch match, Npc n, net.minecraft.entity.Entity e) {
        if (e instanceof ServerPlayerEntity p) {
            var s = match.teams().sideOf(p.getUuid());
            return s != null && s != n.side;
        }
        if (e.getCommandTags().contains("bf.side.att")) {
            return n.side != Side.ATTACKER;
        }
        if (e.getCommandTags().contains("bf.side.def")) {
            return n.side != Side.DEFENDER;
        }
        return false;
    }

    /** 扫描最近敌对目标（真人玩家 + 敌方 NPC）。 */
    private LivingEntity findFoe(ServerMatch match, MinecraftServer server, Npc n, long now) {
        LivingEntity best = null;
        double bestD = SCAN_RANGE * SCAN_RANGE;
        ServerWorld w = server.getOverworld();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            var s = match.teams().sideOf(p.getUuid());
            if (s == null || s == n.side) {
                continue;
            }
            double d = dxz2(n.lastX, n.lastZ, p.getX(), p.getZ());
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        for (Npc o : units.values()) {
            if (o == n || o.side == n.side) {
                continue;
            }
            var e = w.getEntity(o.id);
            if (e == null) {
                continue;
            }
            double d = dxz2(n.lastX, n.lastZ, e.getX(), e.getZ());
            if (d < bestD) {
                bestD = d;
                best = (LivingEntity) e;
            }
        }
        return best;
    }

    /** 交战：导航追敌 → 进入交战距离则停导航原地开火（空手近战 / 持械远程）。
     *  仅攻击与换目标由本模块决策，位移完全交给原版导航 AI。 */
    private void engage(ServerMatch match, MinecraftServer server, Npc n, MobEntity mob, LivingEntity foe, long now) {
        double dx = foe.getX() - mob.getX();
        double dz = foe.getZ() - mob.getZ();
        double dist = Math.hypot(dx, dz);
        boolean hasGun = hasWeapon(server, n);
        double engage = hasGun ? GUN_ENGAGE : MELEE_ENGAGE;
        if (dist > engage) {
            // 超出交战距离 → 导航追敌（导航自带避障与地形跟随）
            startMove(server, n, mob, foe.getX(), foe.getY(), foe.getZ(), MOVE_SPEED);
            return;
        }
        // 进入交战距离：停下导航，原地站桩开火（保持每 interval 造成伤害与挥臂）
        mob.getNavigation().stop();
        faceTarget(mob, dx, dz);
        if (now < n.atkAtMs) {
            return;
        }
        if (hasGun) {
            if (dist <= GUN_RANGE && lineOfSight(server, n, foe, dist)) {
                n.atkAtMs = now + GUN_ATK_MS;
                strikeFoe(server, n, foe, 7.0, false); // 模拟枪械命中（平衡值后续调）
            }
        } else if (dist <= MELEE_RANGE + 0.4) {
            n.atkAtMs = now + MELEE_ATK_MS;
            strikeFoe(server, n, foe, 3.0, true); // 空手近战挥击
        }
    }

    /** 转向目标（导航期间随移动自动转向；此处仅用于站桩开火时手动对敌）。 */
    private void faceTarget(MobEntity mob, double dx, double dz) {
        float yaw = (float) Math.toDegrees(Math.atan2(dx, dz));
        mob.setYaw(yaw);
        mob.setBodyYaw(yaw);
        mob.setHeadYaw(yaw);
    }

    /** 主手是否持有武器（模组物品一律视为武器；原版剑也算近战武器）。 */
    private boolean hasWeapon(MinecraftServer server, Npc n) {
        var w = server.getOverworld();
        var e = w.getEntity(n.id);
        if (!(e instanceof LivingEntity le)) {
            return true; // 找不到实体时按持械处理（保守）
        }
        var stack = le.getMainHandStack();
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Identifier id = Registries.ITEM.getId(stack.getItem());
        if ("minecraft".equals(id.getNamespace())) {
            return stack.getItem() instanceof SwordItem;
        }
        return true; // 模组/枪包物品视为武器
    }

    /** 造成伤害（mob 来源，若击杀走 vanilla 事件流计入击杀归属）。melee 同时挥击主手。 */
    private void strikeFoe(MinecraftServer server, Npc n, LivingEntity foe, double amount, boolean melee) {
        var w = server.getOverworld();
        var attacker = w.getEntity(n.id);
        if (!(attacker instanceof LivingEntity le)) {
            return;
        }
        if (melee) {
            le.swingHand(net.minecraft.util.Hand.MAIN_HAND);
        }
        var src = le.getDamageSources().mobAttack(le);
        foe.damage(src, (float) amount);
    }

    /** 非战斗阶段：清目标 + 停导航 + 清零速度，使 bot 原地站立待命。 */
    private void idleAll(MinecraftServer server) {
        if (units.isEmpty()) {
            return;
        }
        var w = server.getOverworld();
        for (Npc n : units.values()) {
            var e = w.getEntity(n.id);
            if (!(e instanceof MobEntity mob)) {
                continue;
            }
            mob.getNavigation().stop();
            mob.setTarget(null);
            mob.setVelocity(0.0, 0.0, 0.0);
            n.stuckSinceMs = 0; // 重新进入战斗后重新计卡死窗口
        }
    }

    /** 卡死兜底：每决策帧比对水平位移；4s 内位移 <0.5m 且距当前导航目标 >8m，
     *  视为卡墙，一次 /tp 到目标附近安全地面点并重置计时（不逐帧 tp，避免回到老问题）。 */
    private void stuckCheck(MinecraftServer server, Npc n, MobEntity mob, long now) {
        // 仅当导航正在执行（有路径、未在站桩）时才计卡死；否则跳过并复位
        if (mob.getNavigation().isIdle()) {
            n.stuckSinceMs = 0;
            n.prevMoveX = mob.getX();
            n.prevMoveZ = mob.getZ();
            return;
        }
        double move = Math.hypot(mob.getX() - n.prevMoveX, mob.getZ() - n.prevMoveZ);
        double goalDist = Math.hypot(n.goalX - mob.getX(), n.goalZ - mob.getZ());
        if (n.stuckSinceMs == 0) {
            n.stuckSinceMs = now;
        }
        if (now - n.stuckSinceMs >= STUCK_MS) {
            if (move < STUCK_MOVE && goalDist > STUCK_GOAL) {
                double sx = n.goalX;
                double sz = n.goalZ;
                double sy = groundY(server, sx, sz) + 1.0;
                mob.refreshPositionAndAngles(sx, sy, sz, mob.getYaw(), 0.0f);
                exec(server, tpCmd(n, sx, sy, sz, mob.getYaw()));
                n.stuckSinceMs = now; // 重置窗口，给导航一次重新寻路的机会
            } else {
                n.stuckSinceMs = now; // 位移正常，滑动窗口
            }
        }
        n.prevMoveX = mob.getX();
        n.prevMoveZ = mob.getZ();
    }


    /** 视线：npc 眼部到敌人眼部是否被实心方块阻挡。 */
    private boolean lineOfSight(MinecraftServer server, Npc n, LivingEntity foe, double dist) {
        var w = server.getOverworld();
        var e = w.getEntity(n.id);
        if (!(e instanceof LivingEntity le)) {
            return true;
        }
        Vec3d from = le.getEyePos();
        Vec3d to = foe.getEyePos();
        var ctx = new net.minecraft.world.RaycastContext(from, to,
                net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                net.minecraft.world.RaycastContext.FluidHandling.NONE, le);
        var hit = w.raycast(ctx);
        if (hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS) {
            return true;
        }
        return hit.getPos().squaredDistanceTo(from) > dist * dist * 0.96;
    }

    private static double dxz2(double ax, double az, double bx, double bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return dx * dx + dz * dz;
    }

    /** 无敌人：导航前往占点目标（攻方首个点 / 守方分散占点）。到达(≤1.5m)即停导航驻守。 */
    private void moveToObjective(ServerMatch match, MinecraftServer server, Npc n, MobEntity mob) {
        Vec3d target = targetFor(match, server, n);
        if (target == null) {
            return;
        }
        double dist = Math.hypot(target.x - mob.getX(), target.z - mob.getZ());
        if (dist <= ARRIVE_RADIUS) {
            mob.getNavigation().stop(); // 已在点内：停导航，原地驻守
            return;
        }
        startMove(server, n, mob, target.x, target.y, target.z, MOVE_SPEED);
    }

    /**
     * 用原版导航走向目标点；不可达时左右偏航 45° 各重试一次；仍不可达则原地驻守并朝向目标。
     * 返回 true=已下发导航路径。
     */
    private boolean startMove(MinecraftServer server, Npc n, MobEntity mob,
                              double x, double y, double z, double speed) {
        n.goalX = x;
        n.goalY = y;
        n.goalZ = z;
        n.stuckSinceMs = (n.stuckSinceMs == 0) ? System.currentTimeMillis() : n.stuckSinceMs;
        if (mob.getNavigation().startMovingTo(x, y, z, speed)) {
            return true;
        }
        // 不可达：以当前→目标方向为基准，左右各偏 45° 试一个偏移点
        double ang = Math.atan2(z - mob.getZ(), x - mob.getX());
        final double off = 6.0;
        for (double s : new double[]{-1.0, 1.0}) {
            double a = ang + s * Math.PI / 4.0;
            double nx = mob.getX() + Math.cos(a) * off;
            double nz = mob.getZ() + Math.sin(a) * off;
            double ny = groundY(server, nx, nz) + 1.0;
            if (mob.getNavigation().startMovingTo(nx, ny, nz, speed)) {
                n.goalX = nx;
                n.goalY = ny;
                n.goalZ = nz;
                return true;
            }
        }
        // 仍不可达：停导航、原地驻守、转向目标（并尝试跳跃越障）
        mob.getNavigation().stop();
        mob.getJumpControl().setActive();
        double dx = x - mob.getX();
        double dz = z - mob.getZ();
        if (dx != 0 || dz != 0) {
            faceTarget(mob, dx, dz);
        }
        return false;
    }

    /** 攻方：当前扇区首个点；守方：按单位 id 分散到当前扇区各点。
     *  目标点在 zone 内附加「单位稳定偏移」（hash 派生）→ 同队多人站位自然散开，
     *  避免全部叠在圆心同一点。 */
    private Vec3d targetFor(ServerMatch match, MinecraftServer server, Npc n) {
        var zones = match.game().currentSector().zones();
        if (zones.isEmpty()) {
            return null;
        }
        int pick = n.side == Side.ATTACKER ? 0
                : (n.id.hashCode() & 0x7fffffff) % zones.size();
        var z = zones.get(pick);
        int zoneIdx = match.zoneIndex(z.id());
        double[] c = match.zoneCenter(zoneIdx);
        if (c == null) {
            return null;
        }
        double r = Math.max(2.0, match.zoneRadius(zoneIdx));
        // 稳定伪随机偏移：hash 派生 [-0.75r, +0.75r]
        long h = n.id.hashCode() & 0x7fffffffL;
        double fx = ((h % 1001) / 1000.0 - 0.5) * 2.0 * 0.75 * r;
        double fz = (((h >> 16) % 1001) / 1000.0 - 0.5) * 2.0 * 0.75 * r;
        double tx = c[0] + fx;
        double tz = c[1] + fz;
        return new Vec3d(tx, groundY(server, tx, tz) + 1, tz);
    }

    private double groundY(MinecraftServer server, double x, double z) {
        ServerWorld w = server.getOverworld();
        int y = w.getTopY(Heightmap.Type.WORLD_SURFACE, (int) Math.floor(x), (int) Math.floor(z));
        return y <= w.getBottomY() ? 64 : y + 1;
    }

    private static String tpCmd(Npc n, double x, double y, double z, double yaw) {
        return String.format("tp @e[tag=%s%s] %.2f %.2f %.2f %.0f 0", TAG_PREFIX, n.shortId, x, y, z, yaw);
    }

    private static void exec(MinecraftServer server, String cmd) {
        try {
            server.getCommandManager().executeWithPrefix(server.getCommandSource(), cmd);
        } catch (Exception ignored) {
        }
    }
}
