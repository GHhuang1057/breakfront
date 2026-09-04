package com.breakfront.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** S2C：击杀事件推送（供客户端击杀流 HUD）。 */
public record KillFeedPayload(
        String killer,
        String victim,
        boolean attackerDied,
        boolean headshot) implements CustomPayload {

    public static final Id<KillFeedPayload> ID =
            new Id<>(Identifier.of("breakfront", "kill_feed"));

    public static final PacketCodec<PacketByteBuf, KillFeedPayload> CODEC =
            PacketCodec.of(KillFeedPayload::write, KillFeedPayload::new);

    private void write(PacketByteBuf buf) {
        buf.writeString(killer);
        buf.writeString(victim);
        buf.writeBoolean(attackerDied);
        buf.writeBoolean(headshot);
    }

    public KillFeedPayload(PacketByteBuf buf) {
        this(buf.readString(), buf.readString(), buf.readBoolean(), buf.readBoolean());
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
