package com.breakfront.server;

import com.breakfront.game.Side;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W6：TeamManager 补位/离场/兵种记录（纯 JVM）。 */
class TeamManagerTest {

    private static UUID id(int n) {
        return new UUID(0, n);
    }

    @Test
    void assignLeastBalancesTeams() {
        TeamManager tm = new TeamManager();
        // 4 人交替加入：攻守应各 2
        for (int i = 0; i < 4; i++) {
            tm.assignLeast(id(i));
        }
        assertEquals(2, tm.count(Side.ATTACKER));
        assertEquals(2, tm.count(Side.DEFENDER));
    }

    @Test
    void leaveRemovesMembershipAndClass() {
        TeamManager tm = new TeamManager();
        tm.join(id(1), Side.ATTACKER);
        tm.setClass(id(1), "engineer");
        assertEquals("engineer", tm.classOf(id(1)));
        tm.leave(id(1));
        assertNull(tm.sideOf(id(1)));
    }

    @Test
    void unknownClassFallsBackToDefault() {
        TeamManager tm = new TeamManager();
        tm.setClass(id(9), "sniper_broken_id");
        assertEquals("assault", tm.classOf(id(9)));
        tm.setClass(id(9), "recon");
        assertEquals("recon", tm.classOf(id(9)));
        assertTrue(tm.isEmpty());
    }
}
