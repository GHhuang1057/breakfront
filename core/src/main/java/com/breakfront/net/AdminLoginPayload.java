package com.breakfront.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S（M8）：管理员登录 —— 客户端管理员门禁屏提交密码。
 * 服务端校验通过则建立超时会话（不提升 op），并向该玩家回发 AdminLoginResultPayload。
 */
public record AdminLoginPayload(String password) implements CustomPayload {

    public static final Id<AdminLoginPayload> ID =
            new Id<>(Identifier.of("breakfront", "admin_login"));

    public static final PacketCodec<PacketByteBuf, AdminLoginPayload> CODEC =
            PacketCodec.of(AdminLoginPayload::write, AdminLoginPayload::new);

    private void write(PacketByteBuf buf) {
        buf.writeString(password == null ? "" : password, 128);
    }

    public AdminLoginPayload(PacketByteBuf buf) {
        this(buf.readString(128));
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
