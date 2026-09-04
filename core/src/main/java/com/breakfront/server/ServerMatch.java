package com.breakfront.server;

import com.breakfront.game.BreakthroughGame;
import com.breakfront.game.Sector;
import com.breakfront.game.ZoneState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

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
        if (game.phase() != com.breakfront.game.MatchPhase.BATTLE) {
            return;
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
                com.breakfront.game.Side side = teams.sideOf(player.getUuid());
                if (side == null) {
                    continue;
                }
                if (anchor.contains(player.getX(), player.getZ())) {
                    if (side == com.breakfront.game.Side.ATTACKER) {
                        attackers++;
                    } else {
                        defenders++;
                    }
                }
            }
            game.applyZonePresence(idx, attackers, defenders, 0.05);
        }
    }

    public BreakthroughGame game() {
        return game;
    }

    public TeamManager teams() {
        return teams;
    }
}
