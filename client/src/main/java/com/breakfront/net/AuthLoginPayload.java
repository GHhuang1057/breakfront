package com.breakfront.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S：玩家提交 Geekhonize 令牌，请求与账号绑定（防自报名冒名）。
 * 服务端经 Geekhonize Auth（/api/v1/auth/me）校验后回 AuthResultPayload。
 */
public record AuthLoginPayload(String token) implements CustomPayload {

    public static final Id<AuthLoginPayload> ID =
            new Id<>(Identifier.of("breakfront", "auth_login"));

    public static final PacketCodec<PacketByteBuf, AuthLoginPayload> CODEC =
            PacketCodec.of(AuthLoginPayload::write, AuthLoginPayload::new);

    private void write(PacketByteBuf buf) {
        buf.writeString(token == null ? "" : token);
    }

    public AuthLoginPayload(PacketByteBuf buf) {
        this(buf.readString(512));
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
