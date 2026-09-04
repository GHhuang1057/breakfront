package com.breakfront.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C：对局状态同步帧（服务端按节流周期广播给全部玩家）。
 * 只带「当前扇区」的据点明细；每据点附带世界坐标/半径/字母，
 * 供客户端做 BF 式屏幕标记（菱形字母图标、投影定位、屏缘指示）与地面描边。
 */
public record MatchStatePayload(
        int phaseOrdinal,
        int attackerTickets,
        float matchRemainingSeconds,
        float countdownRemainingSeconds,
        int sectorIndex,
        int sectorCount,
        List<ZoneStateView> currentSectorZones,
        int attackerOnline,
        int defenderOnline) implements CustomPayload {

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
            zone.write(buf);
        }
        buf.writeInt(attackerOnline);
        buf.writeInt(defenderOnline);
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
                readZones(buf),
                buf.readInt(),
                buf.readInt());
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

    /**
     * 单据点快照。
     *
     * @param zoneId      内部 ID（A1/A2/B1…，服务端逻辑用）
     * @param letter      BF 风格全局字母（A/B/C…，屏幕菱形标记显示用）
     * @param ownerOrdinal 归属方（Side.ordinal()）
     * @param meter       攻方推进度 0..1
     * @param worldX      据点圆心 X（方块中心）
     * @param worldZ      据点圆心 Z
     * @param groundY     据点地表高度（信标底座所在 Y，用于描边环与标记浮空基准）
     * @param radius      占点判定半径
     */
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
