package com.breakfront.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C：扇区编辑器预览（服务端 → 打开 /bfs 的玩家）。client 编译期桩。
 */
public record SectorEditPayload(
        boolean enabled,
        int currentSector,
        List<ZoneView> zones,
        double attX, double attZ,
        double defX, double defZ,
        double lobbyX, double lobbyZ) implements CustomPayload {

    /** 未设置出生点时对应字段写 NaN（客户端据此跳过绘制）。 */
    public static final double NO_SPAWN = Double.NaN;

    public static SectorEditPayload of(boolean enabled, int currentSector, List<ZoneView> zones,
                                      double attX, double attZ, double defX, double defZ,
                                      double lobbyX, double lobbyZ) {
        return new SectorEditPayload(enabled, currentSector, zones,
                attX, attZ, defX, defZ, lobbyX, lobbyZ);
    }

    public static final Id<SectorEditPayload> ID =
            new Id<>(Identifier.of("breakfront", "sector_edit"));

    public static final PacketCodec<PacketByteBuf, SectorEditPayload> CODEC =
            PacketCodec.of(SectorEditPayload::write, SectorEditPayload::new);

    private void write(PacketByteBuf buf) {
        buf.writeBoolean(enabled);
        buf.writeInt(currentSector);
        buf.writeInt(zones.size());
        for (ZoneView zone : zones) {
            zone.write(buf);
        }
        buf.writeDouble(attX);
        buf.writeDouble(attZ);
        buf.writeDouble(defX);
        buf.writeDouble(defZ);
        buf.writeDouble(lobbyX);
        buf.writeDouble(lobbyZ);
    }

    public SectorEditPayload(PacketByteBuf buf) {
        this(buf.readBoolean(), buf.readInt(), readZones(buf),
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    private static List<ZoneView> readZones(PacketByteBuf buf) {
        int n = buf.readInt();
        List<ZoneView> zones = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            zones.add(ZoneView.read(buf));
        }
        return zones;
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public record ZoneView(
            String zoneId,
            int sectorIndex,
            double worldX,
            double groundY,
            double worldZ,
            double radius) {

        void write(PacketByteBuf buf) {
            buf.writeString(zoneId);
            buf.writeInt(sectorIndex);
            buf.writeDouble(worldX);
            buf.writeDouble(groundY);
            buf.writeDouble(worldZ);
            buf.writeDouble(radius);
        }

        static ZoneView read(PacketByteBuf buf) {
            return new ZoneView(
                    buf.readString(),
                    buf.readInt(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble());
        }
    }
}
