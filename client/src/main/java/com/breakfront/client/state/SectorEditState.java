package com.breakfront.client.state;

import com.breakfront.net.SectorEditPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端扇区编辑器状态单例：接收 /bfs 会话的服务端预览帧，供世界渲染画地面圆环。
 * 仅渲染线程读写（接收回调已切主线程）。
 */
public final class SectorEditState {

    private static boolean enabled;
    private static int currentSector;
    private static final List<ZoneView> zones = new ArrayList<>();

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
    }

    public static void reset() {
        enabled = false;
        currentSector = 0;
        zones.clear();
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

    public record ZoneView(String zoneId, int sectorIndex,
                           double worldX, double groundY, double worldZ, double radius) {
    }
}
