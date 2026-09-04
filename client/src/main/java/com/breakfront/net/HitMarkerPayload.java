package com.breakfront.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * S2C：命中反馈（HitMarker）。
 * kind: 0=命中(白X) 2=击杀(红X大) 3=爆头击杀(黄X特大)。
 * 服务端在「由本玩家造成的伤害/击杀」时发给该玩家。
 */
public record HitMarkerPayload(int kind) implements CustomPayload {

    public static final Id<HitMarkerPayload> ID =
            new Id<>(Identifier.of("breakfront", "hit_marker"));

    public static final PacketCodec<PacketByteBuf, HitMarkerPayload> CODEC =
            PacketCodec.of(HitMarkerPayload::write, HitMarkerPayload::new);

    private void write(PacketByteBuf buf) {
        buf.writeInt(kind);
    }

    public HitMarkerPayload(PacketByteBuf buf) {
        this(buf.readInt());
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
