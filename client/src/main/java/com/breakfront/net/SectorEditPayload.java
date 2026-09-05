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
        List<ZoneView> zones) implements CustomPayload {

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
    }

    public SectorEditPayload(PacketByteBuf buf) {
        this(buf.readBoolean(), buf.readInt(), readZones(buf));
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
