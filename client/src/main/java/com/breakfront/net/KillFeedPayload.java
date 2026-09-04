package com.breakfront.net;

import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 编译期桩 —— core 的 KillFeedPayload（S2C 击杀流）最小签名。
 * 绝不打包（见 client/build.gradle Jar exclude）；运行时由 core 模组提供真实类型。
 */
public class KillFeedPayload implements CustomPayload {

    public static final CustomPayload.Id<KillFeedPayload> ID =
            new CustomPayload.Id<>(Identifier.of("breakfront", "kill_feed"));

    private final String killer;
    private final String victim;
    private final boolean attackerDied;
    private final boolean headshot;

    public KillFeedPayload(String killer, String victim, boolean attackerDied, boolean headshot) {
        this.killer = killer;
        this.victim = victim;
        this.attackerDied = attackerDied;
        this.headshot = headshot;
    }

    public String killer() {
        return killer;
    }

    public String victim() {
        return victim;
    }

    public boolean attackerDied() {
        return attackerDied;
    }

    public boolean headshot() {
        return headshot;
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
