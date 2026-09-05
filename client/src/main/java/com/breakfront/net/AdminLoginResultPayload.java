package com.breakfront.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * S2C（M8）：管理员登录结果 —— 客户端门禁屏据此显示成功/失败并切换面板。
 */
public record AdminLoginResultPayload(boolean ok, String message) implements CustomPayload {

    public static final Id<AdminLoginResultPayload> ID =
            new Id<>(Identifier.of("breakfront", "admin_login_result"));

    public static final PacketCodec<PacketByteBuf, AdminLoginResultPayload> CODEC =
            PacketCodec.of(AdminLoginResultPayload::write, AdminLoginResultPayload::new);

    private void write(PacketByteBuf buf) {
        buf.writeBoolean(ok);
        buf.writeString(message == null ? "" : message, 256);
    }

    public AdminLoginResultPayload(PacketByteBuf buf) {
        this(buf.readBoolean(), buf.readString(256));
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
