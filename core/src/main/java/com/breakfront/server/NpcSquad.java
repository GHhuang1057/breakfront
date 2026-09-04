package com.breakfront.server;

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

    private static final class Npc {
        Side side;
        String shortId;
        UUID id;
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
        String label = side == Side.ATTACKER ? "攻方增援" : "守方增援";
        e.setCustomName(Text.literal("[" + label + "] AI-" + sid));
        e.setCustomNameVisible(true);
        e.setAiDisabled(true);      // 关闭原版 AI：不会乱咬人
        e.setNoGravity(true);       // 位移由命令驱动，防掉落/卡角
        e.addCommandTag("breakfront.npc");
        e.addCommandTag("bf.side." + (side == Side.ATTACKER ? "att" : "def"));
        e.addCommandTag(TAG_PREFIX + sid);
        e.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MAX_HEALTH)
                .setBaseValue(40);
        e.setHealth(40);
        e.setPosition(p.x, p.y, p.z);
        world.spawnEntity(e);

        Npc n = new Npc();
        n.side = side;
        n.shortId = sid;
        n.id = e.getUuid();
        n.lastX = p.x;
        n.lastY = p.y;
        n.lastZ = p.z;
        units.put(n.id, n);
        BreakfrontServer.LOGGER.info("[Breakfront] npc {} spawned ({}) alive={}",
                label + " AI-" + sid, side.labelCn, units.size());
    }

    private Vec3d spawnPos(ServerMatch match, MinecraftServer server, Side side) {
        double[] s = match.spawnsFor(side, server.getOverworld());
        return new Vec3d(s[0], s[1], s[2]);
    }

    // ---------- 每 tick 推进 ----------

    public void tick(ServerMatch match, MinecraftServer server) {
        if (units.isEmpty() || match.game().phase() != MatchPhase.BATTLE) {
            return;
        }
        stepCounter++;
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
            double nx = n.lastX + dx / dist * 0.6;
            double nz = n.lastZ + dz / dist * 0.6;
            double ny = groundY(server, nx, nz) + 0.1;
            n.lastX = nx;
            n.lastY = ny;
            n.lastZ = nz;
            double yaw = Math.toDegrees(Math.atan2(dx, dz));
            exec(server, tpCmd(n, nx, ny, nz, yaw));
        }
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
