package com.breakfront.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C：玩家位置帧（用于 BF 式小地图/雷达的友军点）。
 * 服务端节流广播全部在线玩家的阵营/坐标/朝向/存活。
 * 客户端只绘制自己阵营的点（敌情靠目视/据点状态，不泄露位置）。
 */
public record PlayerPosPayload(List<Row> rows) implements CustomPayload {

    public static final Id<PlayerPosPayload> ID =
            new Id<>(Identifier.of("breakfront", "player_pos"));

    public static final PacketCodec<PacketByteBuf, PlayerPosPayload> CODEC =
            PacketCodec.of(PlayerPosPayload::write, PlayerPosPayload::new);

    private void write(PacketByteBuf buf) {
        buf.writeInt(rows.size());
        for (Row r : rows) {
            r.write(buf);
        }
    }

    public PlayerPosPayload(PacketByteBuf buf) {
        this(readRows(buf));
    }

    private static List<Row> readRows(PacketByteBuf buf) {
        int n = buf.readInt();
        List<Row> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rows.add(Row.read(buf));
        }
        return rows;
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    /**
     * @param name         玩家名（客户端与计分板名字匹配，识别自身/阵营）
     * @param sideOrdinal  Side.ordinal()（0=攻方 1=守方）
     * @param x            世界 X
     * @param z            世界 Z
     * @param yaw          朝向（度，雷达箭头旋转）
     * @param alive        是否存活（阵亡点灰显）
     */
    public record Row(String name, int sideOrdinal, double x, double z, float yaw, boolean alive) {

        void write(PacketByteBuf buf) {
            buf.writeString(name);
            buf.writeInt(sideOrdinal);
            buf.writeDouble(x);
            buf.writeDouble(z);
            buf.writeFloat(yaw);
            buf.writeBoolean(alive);
        }

        static Row read(PacketByteBuf buf) {
            return new Row(buf.readString(), buf.readInt(),
                    buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readBoolean());
        }
    }
}
