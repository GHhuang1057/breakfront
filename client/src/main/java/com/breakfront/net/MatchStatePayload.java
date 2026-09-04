package com.breakfront.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端编译期桩（与 core 真类签名完全一致；运行时由 core jar 提供）。
 * S2C：对局状态同步帧 —— 携带当前扇区据点明细（含世界坐标/半径/字母），
 * 供客户端 BF 式屏幕标记与地面描边渲染。
 */
public record MatchStatePayload(
        int phaseOrdinal,
        int attackerTickets,
        float matchRemainingSeconds,
        float countdownRemainingSeconds,
        int sectorIndex,
        int sectorCount,
        List<ZoneStateView> currentSectorZones) implements CustomPayload {

    public static final Id<MatchStatePayload> ID =
            new Id<>(Identifier.of("breakfront", "match_state"));

    public static final PacketCodec<PacketByteBuf, MatchStatePayload> CODEC =
            PacketCodec.of(MatchStatePayload::write, MatchStatePayload::new);

    private void write(PacketByteBuf buf) {
        buf.writeInt(phaseOrdinal);
        buf.writeInt(attackerTickets);
        buf.writeFloat(matchRemainingSeconds);
        buf.writeFloat(countdownRemainingSeconds);
        buf.writeInt(sectorIndex);
        buf.writeInt(sectorCount);
        buf.writeInt(currentSectorZones.size());
        for (ZoneStateView zone : currentSectorZones) {
            zone.write(buf);
        }
    }

    public MatchStatePayload(PacketByteBuf buf) {
        this(
                buf.readInt(),
                buf.readInt(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readInt(),
                buf.readInt(),
                readZones(buf));
    }

    private static List<ZoneStateView> readZones(PacketByteBuf buf) {
        int n = buf.readInt();
        List<ZoneStateView> zones = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            zones.add(ZoneStateView.read(buf));
        }
        return zones;
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public record ZoneStateView(
            String zoneId,
            String letter,
            int ownerOrdinal,
            float meter,
            double worldX,
            double worldZ,
            double groundY,
            float radius) {

        void write(PacketByteBuf buf) {
            buf.writeString(zoneId);
            buf.writeString(letter);
            buf.writeInt(ownerOrdinal);
            buf.writeFloat(meter);
            buf.writeDouble(worldX);
            buf.writeDouble(worldZ);
            buf.writeDouble(groundY);
            buf.writeFloat(radius);
        }

        static ZoneStateView read(PacketByteBuf buf) {
            return new ZoneStateView(
                    buf.readString(),
                    buf.readString(),
                    buf.readInt(),
                    buf.readFloat(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readFloat());
        }
    }
}
