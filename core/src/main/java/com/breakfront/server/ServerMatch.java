package com.breakfront.server;

import com.breakfront.game.BreakthroughGame;
import com.breakfront.game.MatchPhase;
import com.breakfront.game.Sector;
import com.breakfront.game.Side;
import com.breakfront.game.ZoneState;
import com.breakfront.net.MatchStatePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端对局运行时：把纯逻辑 BreakthroughGame 与真实玩家/世界接起来。
 *
 * - 每 tick 调用 game.tick(0.05)
 * - 按 ZoneAnchor 统计圈内双方人数并喂给占点逻辑
 * - 默认竞技场（打靶房占位）：2 扇区 3 据点，坐标贴近原点便于摆桩验证
 */
public final class ServerMatch {

    private final TeamManager teams = new TeamManager();
    private final BreakthroughGame game;
    private final Map<String, ZoneAnchor> anchors = new LinkedHashMap<>();
    private final List<String> zoneOrder = new ArrayList<>();
    private int syncCounter = 0; // 状态广播节流：每 10 tick 一次
    private boolean visualsPlaced = false; // 据点空间标识（信标+地环）只放一次

    public ServerMatch() {
        List<Sector> sectors = defaultSectors();
        this.game = new BreakthroughGame(sectors);
        indexAnchors();
    }

    private List<Sector> defaultSectors() {
        double captureSeconds = com.breakfront.game.BreakthroughTuning.DEFAULT_ZONE_CAPTURE_SECONDS;
        return List.of(
                new Sector("s1", "扇区一", List.of(
                        new ZoneState("A1", captureSeconds),
                        new ZoneState("A2", captureSeconds))),
                new Sector("s2", "扇区二", List.of(
                        new ZoneState("B1", captureSeconds))));
    }

    private void indexAnchors() {
        anchors.clear();
        zoneOrder.clear();
        double[][] spots = {
                {8.5, 8.5}, {24.5, 8.5}, {40.5, 8.5}
        };
        int i = 0;
        for (Sector sector : game.sectors()) {
            for (ZoneState zone : sector.zones()) {
                anchors.put(zone.id(), new ZoneAnchor(zone.id(), spots[i][0], spots[i][1], 6.0));
                zoneOrder.add(zone.id());
                i++;
            }
        }
    }

    /** 服务端主循环适配（20tps × 0.05s）。 */
    public void tick(MinecraftServer server) {
        game.tick(0.05);
        syncCounter++;
        boolean syncTick = syncCounter % 10 == 0; // 每 0.5s 广播一次状态
        if (syncTick && !server.getPlayerManager().getPlayerList().isEmpty()) {
            broadcastState(server);
        }
        if (game.phase() != MatchPhase.BATTLE) {
            return;
        }
        if (!visualsPlaced) {
            placeZoneVisuals(server.getOverworld());
            visualsPlaced = true;
        }
        var overworld = server.getOverworld();
        for (int idx = 0; idx < zoneOrder.size(); idx++) {
            ZoneAnchor anchor = anchors.get(zoneOrder.get(idx));
            int attackers = 0;
            int defenders = 0;
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (player.getWorld() != overworld || player.isSpectator()) {
                    continue;
                }
                Side side = teams.sideOf(player.getUuid());
                if (side == null) {
                    continue;
                }
                if (anchor.contains(player.getX(), player.getZ())) {
                    if (side == Side.ATTACKER) {
                        attackers++;
                    } else {
                        defenders++;
                    }
                }
            }
            game.applyZonePresence(idx, attackers, defenders, 0.05);
        }
    }

    /** 首次进入战斗时放置据点空间标识：中心信标光柱 + 橙色地面圆盘。 */
    private void placeZoneVisuals(ServerWorld world) {
        for (ZoneAnchor anchor : anchors.values()) {
            int cx = (int) anchor.x();
            int cz = (int) anchor.z();
            int topY = world.getTopY(Heightmap.Type.WORLD_SURFACE, cx, cz);
            if (topY <= world.getBottomY()) {
                continue;
            }
            world.setBlockState(new BlockPos(cx, topY + 1, cz), Blocks.BEACON.getDefaultState(), 3);
            int r = (int) Math.ceil(anchor.radius());
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > anchor.radius() * anchor.radius()) {
                        continue;
                    }
                    int ty = world.getTopY(Heightmap.Type.WORLD_SURFACE, cx + dx, cz + dz);
                    if (ty <= world.getBottomY()) {
                        continue;
                    }
                    world.setBlockState(new BlockPos(cx + dx, ty + 1, cz + dz),
                            Blocks.ORANGE_CONCRETE.getDefaultState(), 3);
                }
            }
        }
    }

    /** 据点锚点坐标摘要（供 /bf status 展示，方便传送验证）。 */
    public String zoneAnchorsText() {
        StringBuilder sb = new StringBuilder();
        for (String id : zoneOrder) {
            ZoneAnchor a = anchors.get(id);
            sb.append('\n').append(a.zoneId()).append(" @ (x=")
                    .append(String.format("%.1f", a.x())).append(", z=")
                    .append(String.format("%.1f", a.z())).append(", r=")
                    .append(String.format("%.0f", a.radius())).append(')');
        }
        return sb.toString();
    }

    /** 打包并广播对局状态给所有在线玩家。 */
    private void broadcastState(MinecraftServer server) {
        var zones = new ArrayList<MatchStatePayload.ZoneStateView>();
        for (ZoneState zone : game.currentSector().zones()) {
            zones.add(new MatchStatePayload.ZoneStateView(
                    zone.id(), zone.owner().ordinal(), (float) zone.meter()));
        }
        var payload = new MatchStatePayload(
                game.phase().ordinal(),
                game.attackerTickets(),
                (float) game.matchRemaining(),
                (float) game.countdownRemaining(),
                game.sectorIndex(),
                game.sectors().size(),
                zones);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    public BreakthroughGame game() {
        return game;
    }

    public TeamManager teams() {
        return teams;
    }
}
