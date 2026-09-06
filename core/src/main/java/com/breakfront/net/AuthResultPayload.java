package com.breakfront.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * S2C：Geekhonize 账号绑定结果（登录/注销/查询）。
 * op: 0=绑定结果 1=注销结果 2=当前身份查询。
 */
public record AuthResultPayload(int op, boolean ok, String username, String rolesCsv, String message)
        implements CustomPayload {

    public static final Id<AuthResultPayload> ID =
            new Id<>(Identifier.of("breakfront", "auth_result"));

    public static final PacketCodec<PacketByteBuf, AuthResultPayload> CODEC =
            PacketCodec.of(AuthResultPayload::write, AuthResultPayload::new);

    private void write(PacketByteBuf buf) {
        buf.writeInt(op);
        buf.writeBoolean(ok);
        buf.writeString(username == null ? "" : username);
        buf.writeString(rolesCsv == null ? "" : rolesCsv);
        buf.writeString(message == null ? "" : message);
    }

    public AuthResultPayload(PacketByteBuf buf) {
        this(buf.readInt(), buf.readBoolean(), buf.readString(64),
                buf.readString(120), buf.readString(256));
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
