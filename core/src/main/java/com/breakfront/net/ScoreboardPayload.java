package com.breakfront.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C：比分与击杀榜（服务端周期广播，客户端计分板/结算界面用）。
 */
public record ScoreboardPayload(
        int attackerKills,
        int defenderKills,
        List<Row> rows) implements CustomPayload {

    public static final Id<ScoreboardPayload> ID =
            new Id<>(Identifier.of("breakfront", "scoreboard"));

    public static final PacketCodec<PacketByteBuf, ScoreboardPayload> CODEC =
            PacketCodec.of(ScoreboardPayload::write, ScoreboardPayload::new);

    public record Row(String name, int sideOrdinal, int kills, int deaths, int headshots) {
    }

    private void write(PacketByteBuf buf) {
        buf.writeInt(attackerKills);
        buf.writeInt(defenderKills);
        buf.writeInt(rows.size());
        for (Row r : rows) {
            buf.writeString(r.name());
            buf.writeInt(r.sideOrdinal());
            buf.writeInt(r.kills());
            buf.writeInt(r.deaths());
            buf.writeInt(r.headshots());
        }
    }

    public ScoreboardPayload(PacketByteBuf buf) {
        this(buf.readInt(), buf.readInt(), readRows(buf));
    }

    private static List<Row> readRows(PacketByteBuf buf) {
        int n = buf.readInt();
        List<Row> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new Row(buf.readString(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt()));
        }
        return out;
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
