package com.breakfront.client.state;

import com.breakfront.net.SectorEditPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端扇区编辑器状态单例：接收 /bfs 会话的服务端预览帧，供世界渲染画地面圆环与出生点。
 * 仅渲染线程读写（接收回调已切主线程）。
 */
public final class SectorEditState {

    private static boolean enabled;
    private static int currentSector;
    private static final List<ZoneView> zones = new ArrayList<>();
    /** 出生点（世界坐标，NaN = 未设置，跳过绘制）。 */
    private static double attX = Double.NaN, attZ = Double.NaN;
    private static double defX = Double.NaN, defZ = Double.NaN;
    private static double lobbyX = Double.NaN, lobbyZ = Double.NaN;

    private SectorEditState() {
    }

    public static void apply(SectorEditPayload payload) {
        enabled = payload.enabled();
        currentSector = payload.currentSector();
        zones.clear();
        for (SectorEditPayload.ZoneView z : payload.zones()) {
            zones.add(new ZoneView(z.zoneId(), z.sectorIndex(), z.worldX(),
                    z.groundY(), z.worldZ(), z.radius()));
        }
        attX = payload.attX();
        attZ = payload.attZ();
        defX = payload.defX();
        defZ = payload.defZ();
        lobbyX = payload.lobbyX();
        lobbyZ = payload.lobbyZ();
    }

    public static void reset() {
        enabled = false;
        currentSector = 0;
        zones.clear();
        attX = attZ = defX = defZ = lobbyX = lobbyZ = Double.NaN;
    }

    public static boolean enabled() {
        return enabled;
    }

    public static int currentSector() {
        return currentSector;
    }

    public static List<ZoneView> zones() {
        return zones;
    }

    public static boolean hasAttackerSpawn() {
        return !Double.isNaN(attX);
    }

    public static double attackerSpawnX() {
        return attX;
    }

    public static double attackerSpawnZ() {
        return attZ;
    }

    public static boolean hasDefenderSpawn() {
        return !Double.isNaN(defX);
    }

    public static double defenderSpawnX() {
        return defX;
    }

    public static double defenderSpawnZ() {
        return defZ;
    }

    public static boolean hasLobbySpawn() {
        return !Double.isNaN(lobbyX);
    }

    public static double lobbySpawnX() {
        return lobbyX;
    }

    public static double lobbySpawnZ() {
        return lobbyZ;
    }

    public record ZoneView(String zoneId, int sectorIndex,
                           double worldX, double groundY, double worldZ, double radius) {
    }
}
