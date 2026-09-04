package com.breakfront.game;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** W6：回合循环边界 —— returnToLobby / startRound 幂等 / 结算后可再开局。 */
class GameFlowLoopTest {

    private static BreakthroughGame game() {
        return new BreakthroughGame(List.of(
                new Sector("s1", "扇区一", List.of(
                        new ZoneState("A1", 10.0),
                        new ZoneState("A2", 10.0)))), 250, 1500, 1);
    }

    @Test
    void returnToLobbyFromRoundEndThenStartAgain() {
        BreakthroughGame g = game();
        g.startRound();
        for (int i = 0; i < 40; i++) {
            g.tick(0.05);
        }
        assertEquals(MatchPhase.BATTLE, g.phase());
        g.endRound(MatchResult.ATTACKER_WIN);
        assertEquals(MatchPhase.ROUND_END, g.phase());

        g.returnToLobby();
        assertEquals(MatchPhase.LOBBY, g.phase());
        assertEquals(250, g.attackerTickets()); // 资源已回满

        // 大厅可再次开局（第二轮）
        g.startRound();
        assertEquals(MatchPhase.COUNTDOWN, g.phase());
        assertEquals(1.0, g.countdownRemaining(), 1e-9);
    }

    @Test
    void autoRestartLoopNeverThrowsAfterRepeatedEnd() {
        BreakthroughGame g = game();
        for (int round = 0; round < 5; round++) {
            g.startRound();
            for (int i = 0; i < 30; i++) {
                g.tick(0.05);
            }
            g.endRound(MatchResult.DEFENDER_WIN);
            g.returnToLobby();
            assertEquals(MatchPhase.LOBBY, g.phase(), "round=" + round);
        }
    }

    @Test
    void endRoundDuringCountdownIsValid() {
        BreakthroughGame g = game();
        g.startRound();
        g.endRound(MatchResult.DEFENDER_WIN);
        assertEquals(MatchPhase.ROUND_END, g.phase());
        assertEquals(MatchResult.DEFENDER_WIN, g.result());
    }
}
