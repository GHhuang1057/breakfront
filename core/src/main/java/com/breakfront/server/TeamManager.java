package com.breakfront.server;

import com.breakfront.game.Side;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 阵营登记表（服务端权威）。
 * P1 用指令手动分配，便于「打靶房」验证；后续由大厅/自动平衡接管。
 */
public final class TeamManager {

    private final Map<UUID, Side> membership = new HashMap<>();
    private final Map<UUID, String> classes = new HashMap<>();

    public static final String[] KNOWN_CLASSES = {"assault", "engineer", "support", "recon"};

    public void setClass(UUID playerId, String classId) {
        for (String k : KNOWN_CLASSES) {
            if (k.equals(classId)) {
                classes.put(playerId, k);
                return;
            }
        }
    }

    public String classOf(UUID playerId) {
        return classes.getOrDefault(playerId, "assault");
    }

    public void join(UUID playerId, Side side) {
        membership.put(playerId, side);
    }

    public void leave(UUID playerId) {
        membership.remove(playerId);
    }

    public Side sideOf(UUID playerId) {
        return membership.get(playerId);
    }

    public boolean isAttacker(UUID playerId) {
        return membership.get(playerId) == Side.ATTACKER;
    }

    public long count(Side side) {
        return membership.values().stream().filter(s -> s == side).count();
    }

    /** 自动补位：优先加入人数少的一边（平局倾向攻方），返回分配的阵营。 */
    public Side assignLeast(UUID playerId) {
        Side s = count(Side.ATTACKER) <= count(Side.DEFENDER) ? Side.ATTACKER : Side.DEFENDER;
        join(playerId, s);
        return s;
    }

    public boolean isEmpty() {
        return membership.isEmpty();
    }
}
