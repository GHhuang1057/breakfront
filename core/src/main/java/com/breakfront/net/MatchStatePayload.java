package com.breakfront.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C：对局状态同步帧（服务端按节流周期广播给全部玩家）。
 * 只带「当前扇区」的据点明细，控制包体；完整扇区结构后续再扩。
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

    /** 服务端 → 包：写入的字段顺序与读取严格一致。 */
    private void write(PacketByteBuf buf) {
        buf.writeInt(phaseOrdinal);
        buf.writeInt(attackerTickets);
        buf.writeFloat(matchRemainingSeconds);
        buf.writeFloat(countdownRemainingSeconds);
        buf.writeInt(sectorIndex);
        buf.writeInt(sectorCount);
        buf.writeInt(currentSectorZones.size());
        for (ZoneStateView zone : currentSectorZones) {
            buf.writeString(zone.zoneId());
            buf.writeInt(zone.ownerOrdinal());
            buf.writeFloat(zone.meter());
        }
    }

    /** 客户端 → 构造：从 buf 严格还原。 */
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
            zones.add(new ZoneStateView(buf.readString(), buf.readInt(), buf.readFloat()));
        }
        return zones;
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    /** 单据点快照（owner 用 Side.ordinal() 编码）。 */
    public record ZoneStateView(String zoneId, int ownerOrdinal, float meter) {
    }
}
