package com.breakfront.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S：玩家选择兵种（部署页点击兵种卡触发）。
 * 服务端仅记录该玩家兵种（P3 装备发放时按兵种配枪/装备）。
 * 未知兵种 ID 会被服务端忽略，不会崩服。
 */
public record SetClassPayload(String classId) implements CustomPayload {

    public static final Id<SetClassPayload> ID =
            new Id<>(Identifier.of("breakfront", "set_class"));

    public static final PacketCodec<PacketByteBuf, SetClassPayload> CODEC =
            PacketCodec.of(SetClassPayload::write, SetClassPayload::new);

    private void write(PacketByteBuf buf) {
        buf.writeString(classId);
    }

    public SetClassPayload(PacketByteBuf buf) {
        this(buf.readString(64));
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
