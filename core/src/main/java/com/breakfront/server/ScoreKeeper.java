package com.breakfront.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 战绩统计核心（纯 Java，可单测）。
 * 每局清零；记录玩家击杀/死亡/爆头与双方击杀数；支持取击杀榜。
 */
public final class ScoreKeeper {

    private int attackerKills;
    private int defenderKills;
    private final Map<UUID, Entry> players = new HashMap<>();

    public static final class Entry {
        public String name;
        public int sideOrdinal;
        public int kills;
        public int deaths;
        public int headshots;

        Entry(String name, int sideOrdinal) {
            this.name = name;
            this.sideOrdinal = sideOrdinal;
        }
    }

    public void reset() {
        attackerKills = 0;
        defenderKills = 0;
        players.clear();
    }

    public void record(UUID victimId, String victimName, int victimSideOrd,
                       UUID killerId, String killerName, int killerSideOrd,
                       boolean headshot) {
        Entry v = player(victimId, victimName, victimSideOrd);
        v.deaths++;
        if (killerId != null) {
            Entry k = player(killerId, killerName, killerSideOrd);
            k.kills++;
            if (headshot) {
                k.headshots++;
            }
            if (killerSideOrd == 0) {
                attackerKills++;   // 攻方阵营击杀
            } else {
                defenderKills++;
            }
        }
    }

    private Entry player(UUID id, String name, int sideOrd) {
        Entry e = players.get(id);
        if (e == null) {
            e = new Entry(name, sideOrd);
            players.put(id, e);
        } else {
            e.name = name;
        }
        return e;
    }

    /** 击杀榜前 n（按 kills 降序，同杀比死亡）。 */
    public List<Entry> top(int n) {
        List<Entry> list = new ArrayList<>(players.values());
        list.sort(Comparator.comparingInt((Entry e) -> e.kills).reversed()
                .thenComparingInt(e -> e.deaths));
        return list.size() > n ? list.subList(0, n) : list;
    }

    public int attackerKills() {
        return attackerKills;
    }

    public int defenderKills() {
        return defenderKills;
    }

    public boolean isEmpty() {
        return players.isEmpty();
    }
}
