package com.breakfront.server;

import com.breakfront.game.Side;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoreKeeperTest {

    private static UUID id(int n) {
        return new UUID(0, n);
    }

    @Test
    void killCreditsAndTeamCounts() {
        ScoreKeeper s = new ScoreKeeper();
        s.record(id(1), "A_att", Side.ATTACKER.ordinal(), id(2), "D_def", Side.DEFENDER.ordinal(), true);
        s.record(id(2), "D_def", Side.DEFENDER.ordinal(), null, null, -1, false); // 环境击杀
        assertEquals(0, s.attackerKills());
        assertEquals(1, s.defenderKills());
        assertEquals(1, s.top(10).get(0).deaths);
        List<ScoreKeeper.Entry> top = s.top(10);
        ScoreKeeper.Entry killer = top.stream().filter(e -> e.name.equals("D_def")).findFirst().orElseThrow();
        assertEquals(1, killer.kills);
        assertEquals(1, killer.headshots);
    }

    @Test
    void resetClears() {
        ScoreKeeper s = new ScoreKeeper();
        s.record(id(1), "x", 0, null, null, -1, false);
        s.reset();
        assertTrue(s.isEmpty());
        assertEquals(0, s.attackerKills());
    }

    @Test
    void assignLeastBalances() {
        TeamManager t = new TeamManager();
        assertEquals(Side.ATTACKER, t.assignLeast(id(1))); // 空表 → 攻方
        assertEquals(Side.DEFENDER, t.assignLeast(id(2)));
        assertEquals(1, t.count(Side.ATTACKER));
        assertEquals(1, t.count(Side.DEFENDER));
    }
}
