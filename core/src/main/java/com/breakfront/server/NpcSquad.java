package com.breakfront.server;

import com.breakfront.game.BreakthroughTuning;
import com.breakfront.game.MatchPhase;
import com.breakfront.game.Side;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
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
 * 说明：v0.5 实现 移动/占点/可击杀；找掩体/射击等战术 AI 另立项。
 * 实体复用 zombie（NoAI + 锁重力），由本类以命令驱动位移，避免误伤与乱跑。
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
        e.setAiDisabled(true);      // 关闭原版 AI：不会乱咬人
        e.setNoGravity(true);       // 位移由命令驱动，防掉落/卡角
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
        // 用指令 NBT 语法（与 Kits.giveKit 同源）：1.21.1 下 NBT 标签由原版解析写入
        // CustomData 组件，TaCZ 读取同一键位；规避 Data Component API 直写差异。
        Kits.KitSpec kit = Kits.spec(cls);
        exec(server, String.format(
                "replaceitem entity @e[tag=%s%s,limit=1] weapon.mainhand "
                        + "tacz:modern_kinetic_gun{GunId:\"tacz:%s\",GunCurrentAmmoCount:%d} 1",
                TAG_PREFIX, sid, kit.gunId(), kit.magSize()));
        e.setPosition(p.x, p.y, p.z);
        world.spawnEntity(e);

        Npc n = new Npc();
        n.side = side;
        n.shortId = sid;
        n.id = e.getUuid();
        n.cls = cls;
        n.lastX = p.x;
        n.lastY = p.y;
        n.lastZ = p.z;
        units.put(n.id, n);
        BreakfrontServer.LOGGER.info("[Breakfront] npc {} spawned ({} / {} , {}) alive={}",
                label + " AI-" + sid, side.labelCn, cls, kit.gunId(), units.size());
    }

    private Vec3d spawnPos(ServerMatch match, MinecraftServer server, Side side) {
        double[] s = match.spawnsFor(side, server.getOverworld());
        return new Vec3d(s[0], s[1], s[2]);
    }

    // ---------- 每 tick 推进 ----------

    public void tick(ServerMatch match, MinecraftServer server) {
        if (match.game().phase() != MatchPhase.BATTLE) {
            return;
        }
        stepCounter++;
        if (stepCounter % 20 == 0) {
            sweep(server); // 每秒清扫阵亡单位（顺带消灭「No entity was found」刷屏）
        }
        if (units.isEmpty()) {
            return;
        }
        if (stepCounter % 3 != 0) { // 每 3 tick（0.15s）动一步，兼顾平顺与开销
            return;
        }
        for (Npc n : units.values()) {
            Vec3d target = targetFor(match, server, n);
            if (target == null) {
                continue;
            }
            double dx = target.x - n.lastX;
            double dz = target.z - n.lastZ;
            double dist = Math.hypot(dx, dz);
            if (dist < 1.2) {
                continue; // 已在点内驻守
            }
            double ux = dx / dist;
            double uz = dz / dist;
            // 地形跟随 + 轴分离避障：直行不可行则分别试 x/z 单轴，均不可行则原地驻守
            double[] step = chooseStep(server, n.lastX, n.lastZ, ux, uz);
            if (step == null) {
                continue;
            }
            double yaw = Math.toDegrees(Math.atan2(step[0] - n.lastX, step[1] - n.lastZ));
            n.lastX = step[0];
            n.lastZ = step[1];
            n.lastY = groundY(server, n.lastX, n.lastZ) + 0.1;
            exec(server, tpCmd(n, step[0], n.lastY, step[1], yaw));
        }
    }

    /** 返回下一位置 {x,z}：直行→x 轴→z 轴；条件=目标点地表与当前高度差 ≤3.5。 */
    private double[] chooseStep(MinecraftServer server, double x, double z, double ux, double uz) {
        double g0 = groundY(server, x, z);
        double nx = x + ux * 0.6;
        double nz = z + uz * 0.6;
        if (walkable(server, nx, nz, g0)) {
            return new double[]{nx, nz};
        }
        if (walkable(server, nx, z, g0)) {
            return new double[]{nx, z};
        }
        if (walkable(server, x, nz, g0)) {
            return new double[]{x, nz};
        }
        return null;
    }

    private boolean walkable(MinecraftServer server, double x, double z, double fromGround) {
        double g = groundY(server, x, z);
        return Math.abs(g - fromGround) <= 3.5;
    }

    /** 攻方：当前扇区首个点；守方：按单位 id 分散到当前扇区各点。 */
    private Vec3d targetFor(ServerMatch match, MinecraftServer server, Npc n) {
        var zones = match.game().currentSector().zones();
        if (zones.isEmpty()) {
            return null;
        }
        int pick = n.side == Side.ATTACKER ? 0
                : (n.id.hashCode() & 0x7fffffff) % zones.size();
        int zoneIdx = match.zoneIndex(zones.get(pick).id());
        double[] c = match.zoneCenter(zoneIdx);
        if (c == null) {
            return null;
        }
        return new Vec3d(c[0], groundY(server, c[0], c[1]) + 1, c[1]);
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
