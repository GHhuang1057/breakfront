package com.breakfront.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 管理员会话服务（M8）。
 *
 * 约定：管理员≠原版 op。玩家用密码换取一个带超时的会话（默认 30 分钟），
 * 拥有会话即可通过 /bfs 扇区编辑器与 /bf admin goto 等「编辑器级」操作；
 * 不提升任何原版权限，避免越权。
 *
 * 密码/超时可经 runDir/breakfront-server.properties 覆盖
 * （admin.password / admin.timeout，由 ServerMatch 装载时写入本类）。
 */
public final class AdminService {

    /** 默认密码（properties 可覆盖）。 */
    public static volatile String password = "breakfront";

    /** 会话有效期（秒），默认 30 分钟。 */
    public static volatile long timeoutSecs = 1800;

    /** uuid → 会话到期时间戳（ms）。只应在服务端主线程访问。 */
    private static final Map<UUID, Long> sessions = new HashMap<>();

    private AdminService() {
    }

    /** 校验密码并建立会话；失败返回 false。 */
    public static boolean login(UUID uuid, String candidate) {
        if (uuid == null || candidate == null || !candidate.equals(password)) {
            return false;
        }
        sessions.put(uuid, System.currentTimeMillis() + timeoutSecs * 1000L);
        return true;
    }

    /** 会话是否有效（惰性过期清理）。 */
    public static boolean has(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        Long expireAt = sessions.get(uuid);
        if (expireAt == null) {
            return false;
        }
        if (System.currentTimeMillis() > expireAt) {
            sessions.remove(uuid);
            return false;
        }
        return true;
    }

    public static void logout(UUID uuid) {
        if (uuid != null) {
            sessions.remove(uuid);
        }
    }

    /** 命令源判定：op2 或 管理员会话（供 /bfs 等编辑器命令 gate）。 */
    public static boolean allows(net.minecraft.command.CommandSource source) {
        if (source instanceof net.minecraft.server.command.ServerCommandSource scs) {
            if (scs.hasPermissionLevel(2)) {
                return true;
            }
            if (scs.getEntity() instanceof net.minecraft.server.network.ServerPlayerEntity sp) {
                return has(sp.getUuid());
            }
        }
        return false;
    }

    public static int activeSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> e.getValue() < now);
        return sessions.size();
    }
}
