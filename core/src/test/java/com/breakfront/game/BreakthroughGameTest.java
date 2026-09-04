package com.breakfront.game;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 回合状态机与攻防规则的核心逻辑单测（纯 JVM，无 Minecraft 依赖）。 */
class BreakthroughGameTest {

    private static final double DT = 0.05; // 模拟 20tps 服务端 tick

    private static BreakthroughGame twoSectorGame() {
        // 倒计时压到 1 秒，便于测试快速进入 BATTLE
        return new BreakthroughGame(List.of(
                new Sector("s1", "扇区一", List.of(
                        new ZoneState("A1", 10.0),
                        new ZoneState("A2", 10.0))),
                new Sector("s2", "扇区二", List.of(
                        new ZoneState("B1", 10.0)))), 250, 1500, 1);
    }

    @Test
    void countdownAutoTransitionsToBattle() {
        BreakthroughGame game = new BreakthroughGame(List.of(
                new Sector("s", "扇区一", List.of(new ZoneState("A1", 10.0)))), 10, 100, 5);
        game.startRound();
        assertEquals(MatchPhase.COUNTDOWN, game.phase());

        // 倒计时 5 秒走完
        for (int i = 0; i < 5; i++) {
            game.tick(1.0);
        }
        assertEquals(MatchPhase.BATTLE, game.phase());
        assertEquals(100.0, game.matchRemaining(), 1e-6);
    }

    @Test
    void attackersCaptureZoneThenSectorAdvances() {
        BreakthroughGame game = twoSectorGame();
        game.startRound();
        for (int i = 0; i < 5; i++) {
            game.tick(1.0);
        }
        assertEquals(MatchPhase.BATTLE, game.phase());

        // A1 被攻方占下
        List<ZoneEvent> events = null;
        for (int i = 0; i < 10; i++) {
            events = game.applyZonePresence(0, 1, 0, 1.0);
        }
        assertInstanceOf(ZoneEvent.class, events.get(0));
        assertEquals(Side.ATTACKER, events.get(0).capturedBy());
        // A2 尚未占领 → 仍在扇区一
        assertEquals(0, game.sectorIndex());

        // A2 占下 → 推进到扇区二
        for (int i = 0; i < 10; i++) {
            game.applyZonePresence(1, 1, 0, 1.0);
        }
        assertEquals(1, game.sectorIndex());
        assertEquals(MatchPhase.BATTLE, game.phase());
    }

    @Test
    void capturingLastSectorWinsForAttackers() {
        BreakthroughGame game = twoSectorGame();
        game.startRound();
        for (int i = 0; i < 5; i++) {
            game.tick(1.0);
        }
        // 占满扇区一
        for (int i = 0; i < 10; i++) {
            game.applyZonePresence(0, 1, 0, 1.0);
            game.applyZonePresence(1, 1, 0, 1.0);
        }
        assertEquals(1, game.sectorIndex());
        // 攻下末扇区 B1 → 攻方胜
        for (int i = 0; i < 10; i++) {
            game.applyZonePresence(2, 1, 0, 1.0);
        }
        assertEquals(MatchResult.ATTACKER_WIN, game.result());
        assertEquals(MatchPhase.ROUND_END, game.phase());
    }

    @Test
    void defenderContestSlowsCaptureAndCanRetake() {
        BreakthroughGame game = twoSectorGame();
        game.startRound();
        game.tick(1.0); // 进战斗
        ZoneState a1 = game.sectors().get(0).zones().get(0);

        // 攻方 1v0 推进 5 秒：meter 0 -> 0.5（满表需 10 秒）
        for (int i = 0; i < 5; i++) {
            game.applyZonePresence(0, 1, 0, 1.0);
        }
        assertEquals(0.5, a1.meter(), 1e-6);

        // 守方进场 1v1：diff=0，进度冻结
        game.applyZonePresence(0, 1, 1, 1.0);
        assertEquals(0.5, a1.meter(), 1e-6);

        // 守方 1v0 反压 5 秒：meter 清空回 0（尚未易手，仍在守方手中）
        for (int i = 0; i < 5; i++) {
            game.applyZonePresence(0, 0, 1, 1.0);
        }
        assertEquals(0.0, a1.meter(), 1e-6);
        assertEquals(Side.DEFENDER, a1.owner());

        // 守方继续反压 10 秒：已在自己手里，meter 保持 0、无事件
        ZoneEvent last = null;
        for (int i = 0; i < 10; i++) {
            List<ZoneEvent> ev = game.applyZonePresence(0, 0, 1, 1.0);
            if (!ev.isEmpty()) {
                last = ev.get(0);
            }
        }
        assertNull(last);
        assertEquals(0.0, a1.meter(), 1e-6);
        assertEquals(Side.DEFENDER, a1.owner());
    }

    @Test
    void attackerHoldIsLostAfterFullDrain() {
        BreakthroughGame game = twoSectorGame();
        game.startRound();
        game.tick(1.0);
        ZoneState a1 = game.sectors().get(0).zones().get(0);

        // 攻方占下 A1（meter -> 1.0）
        for (int i = 0; i < 10; i++) {
            game.applyZonePresence(0, 1, 0, 1.0);
        }
        assertEquals(Side.ATTACKER, a1.owner());
        assertEquals(1.0, a1.meter(), 1e-6);

        // 攻方离圈、守方进圈：需完整清空 10 秒才会丢点
        boolean retaken = false;
        for (int i = 0; i < 9; i++) {
            List<ZoneEvent> ev = game.applyZonePresence(0, 0, 1, 1.0);
            retaken |= !ev.isEmpty();
        }
        assertEquals(Side.ATTACKER, a1.owner(), "未满 10 秒不应丢点");
        assertFalse(retaken);
        assertEquals(0.1, a1.meter(), 1e-6);

        List<ZoneEvent> ev = game.applyZonePresence(0, 0, 1, 1.0);
        assertEquals(Side.DEFENDER, a1.owner());
        assertEquals(Side.DEFENDER, ev.get(0).capturedBy());
    }

    @Test
    void ticketsExhaustionEndsRoundForDefenders() {
        BreakthroughGame game = new BreakthroughGame(List.of(
                new Sector("s", "扇区一", List.of(new ZoneState("A1", 10.0)))), 2, 100, 1);
        game.startRound();
        game.tick(1.0); // 进战斗
        game.onAttackerDeath();
        game.onAttackerDeath();
        assertEquals(0, game.attackerTickets());
        assertEquals(MatchResult.DEFENDER_WIN, game.result());
        assertEquals(MatchPhase.ROUND_END, game.phase());
    }

    @Test
    void timeExpiryEndsRoundForDefenders() {
        BreakthroughGame game = new BreakthroughGame(List.of(
                new Sector("s", "扇区一", List.of(new ZoneState("A1", 10.0)))), 50, 10, 1);
        game.startRound();
        game.tick(1.0); // 进战斗，10 秒剩余
        for (int i = 0; i < 10; i++) {
            game.tick(1.0);
        }
        assertEquals(MatchResult.DEFENDER_WIN, game.result());
        assertTrue(game.phase() == MatchPhase.ROUND_END);
    }

    @Test
    void captureProgressOnlyAdvancesDuringBattle() {
        BreakthroughGame game = twoSectorGame();
        game.startRound();
        ZoneState a1 = game.sectors().get(0).zones().get(0);

        // 倒计时阶段圈内有人不产生推进
        List<ZoneEvent> events = game.applyZonePresence(0, 1, 0, 1.0);
        assertTrue(events.isEmpty());
        assertEquals(0.0, a1.meter(), 1e-6);

        // 非法 dt（<=0）在战斗中也不产生推进
        game.tick(1.0); // 进战斗
        assertTrue(game.applyZonePresence(0, 1, 0, 0.0).isEmpty());
        assertEquals(0.0, a1.meter(), 1e-6);

        // 正常战斗中才推进
        game.applyZonePresence(0, 1, 0, 1.0);
        assertEquals(0.1, a1.meter(), 1e-6);
    }

    @Test
    void startRoundResetsEverything() {
        BreakthroughGame game = twoSectorGame();
        game.startRound();
        for (int i = 0; i < 5; i++) {
            game.tick(1.0);
        }
        for (int i = 0; i < 10; i++) {
            game.applyZonePresence(0, 1, 0, 1.0);
            game.applyZonePresence(1, 1, 0, 1.0);
        }
        assertEquals(1, game.sectorIndex());
        assertEquals(250, game.attackerTickets()); // 未死过人

        game.endRound(MatchResult.DEFENDER_WIN);
        game.startRound();
        assertEquals(MatchPhase.COUNTDOWN, game.phase());
        assertEquals(MatchResult.NONE, game.result());
        assertEquals(0, game.sectorIndex());
        assertEquals(250, game.attackerTickets());
        assertEquals(Side.DEFENDER, game.sectors().get(0).zones().get(0).owner());
    }
}
