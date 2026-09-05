package com.breakfront.server;

import com.breakfront.Breakfront;
import com.breakfront.game.BreakthroughGame;
import com.breakfront.game.MatchPhase;
import com.breakfront.game.Sector;
import com.breakfront.game.Side;
import com.breakfront.game.ZoneState;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BREAKFRONT 独立管理控制台（Web Admin Console，2026-09-05 v1）。
 *
 * 管理不进入游戏：本控制台挂在 ModUpdateServer 的 25610 端口上（/bfadmin/*），
 * 运营方用浏览器打开 http://host:25610/bfadmin/ 即可：
 *   - 登录：admin.password（与 AdminService 同源）
 *   - status：对局/据点/AI/玩家 实时快照
 *   - cmd：对局控制动作（start/end/stop/fill/autostart…）——经原版命令桥执行，
 *     复用 /bf 全套既有规则与鉴权（服务器控制台源 = 满权限）。
 *
 * 说明：仅新增 HTTP 面，不触碰对局玩法协议；未来第三方认证(OAuth)接这里即可。
 */
public final class WebAdminConsole {

    private static final SecureRandom RNG = new SecureRandom();
    /** token -> 最近活跃时间戳（30 分钟滑动过期）。 */
    private static final Map<String, Long> TOKENS = new ConcurrentHashMap<>();
    private static final long TTL_MS = 30L * 60 * 1000;
    private static volatile String lastCmdResult = "";

    private WebAdminConsole() {
    }

    /** ModUpdateServer 的 /bfadmin/* context 入口。 */
    public static void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String m = ex.getRequestMethod();
            if (path.equals("/bfadmin/api/login") && m.equalsIgnoreCase("POST")) {
                login(ex);
            } else if (path.equals("/bfadmin/api/status") && m.equalsIgnoreCase("GET")) {
                status(ex);
            } else if (path.equals("/bfadmin/api/map") && m.equalsIgnoreCase("GET")) {
                map(ex);
            } else if (path.equals("/bfadmin/api/mapedit") && m.equalsIgnoreCase("POST")) {
                mapEdit(ex);
            } else if (path.equals("/bfadmin/api/cmd") && m.equalsIgnoreCase("POST")) {
                cmd(ex);
            } else if (path.equals("/bfadmin/") || path.equals("/bfadmin")) {
                byte[] page = readResource("/assets/bfadmin/index.html");
                respond(ex, 200, page, "text/html; charset=utf-8");
            } else {
                respond(ex, 404, "not found".getBytes(StandardCharsets.UTF_8), "text/plain");
            }
        } catch (Exception e) {
            try {
                respond(ex, 500, ("err " + e).getBytes(StandardCharsets.UTF_8), "text/plain");
            } catch (IOException ignored) {
            }
        }
    }

    // ================= API =================

    private static void login(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        String pw = quoted(body, "pw");
        boolean ok = pw != null && AdminService.password != null && pw.equals(AdminService.password);
        String token = null;
        if (ok) {
            token = randomToken();
            TOKENS.put(token, System.currentTimeMillis());
        }
        json(ex, 200, "{\"ok\":" + ok + ",\"token\":\"" + (token == null ? "" : token)
                + "\",\"msg\":\"" + (ok ? "登录成功" : "密码错误") + "\"}");
    }

    private static void status(HttpExchange ex) throws IOException {
        if (!auth(ex)) {
            json(ex, 401, "{\"ok\":false,\"msg\":\"未授权或已过期\"}");
            return;
        }
        MinecraftServer server = BreakfrontServer.server();
        ServerMatch match = BreakfrontServer.match();
        if (server == null || match == null) {
            json(ex, 200, "{\"ok\":true,\"match\":\"offline\"}");
            return;
        }
        BreakthroughGame g = match.game();
        MatchPhase ph = g.phase();
        StringBuilder sb = new StringBuilder("{\"ok\":true,");
        sb.append("\"phase\":\"").append(ph.name()).append("\",");
        sb.append("\"phaseCn\":\"").append(phaseCn(ph)).append("\",");
        sb.append("\"sectorIndex\":").append(g.sectorIndex()).append(',');
        sb.append("\"sectorCount\":").append(g.sectors().size()).append(',');
        sb.append("\"tickets\":").append(g.attackerTickets()).append(',');
        sb.append("\"ticketsMax\":").append(g.attackerTicketsMax()).append(',');
        sb.append("\"matchRemaining\":").append((int) Math.ceil(g.matchRemaining())).append(',');
        sb.append("\"countdown\":").append((int) Math.ceil(g.countdownRemaining())).append(',');
        sb.append("\"autofill\":").append(match.autoFillEnabled()).append(',');
        sb.append("\"autostart\":").append(match.autostartEnabled()).append(',');
        // AI
        NpcSquad npc = match.npc();
        sb.append("\"aiAlive\":").append(npc == null ? 0 : npc.alive()).append(',');
        sb.append("\"aiInfo\":\"").append(esc(npc == null ? "-" : npc.info())).append("\",");
        // 玩家
        sb.append("\"players\":[");
        boolean first = true;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            Side s = match.teams().sideOf(p.getUuid());
            sb.append("{\"name\":\"").append(esc(p.getGameProfile().getName()))
                    .append("\",\"side\":\"").append(s == null ? "-" : s.labelCn)
                    .append("\",\"cls\":\"").append(esc(match.teams().classOf(p.getUuid())))
                    .append("\",\"x\":").append((int) p.getX())
                    .append(",\"y\":").append((int) p.getY())
                    .append(",\"z\":").append((int) p.getZ())
                    .append(",\"hp\":").append((int) Math.ceil(p.getHealth())).append('}');
        }
        sb.append("],\"sector\":\"").append(esc(sectorDesc(g))).append("\",");
        // 据点
        sb.append("\"zones\":[");
        first = true;
        Sector sec = g.currentSector();
        if (sec != null) {
            for (ZoneState z : sec.zones()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append("{\"id\":\"").append(esc(z.id()))
                        .append("\",\"owner\":\"").append(z.owner().name())
                        .append("\",\"meter\":").append(String.format("%.2f", z.meter()))
                        .append(",\"pct\":").append((int) Math.round(z.meter() * 100.0)).append('}');
            }
        }
        sb.append("]}");
        json(ex, 200, sb.toString());
    }

    /** 地图布局快照（扇区顺序+据点坐标+出生点）。 */
    private static void map(HttpExchange ex) throws IOException {
        if (!auth(ex)) {
            json(ex, 401, "{\"ok\":false,\"msg\":\"未授权\"}");
            return;
        }
        MinecraftServer server = BreakfrontServer.server();
        ServerMatch match = BreakfrontServer.match();
        if (server == null || match == null) {
            json(ex, 200, "{\"ok\":false,\"msg\":\"服务端未就绪\"}");
            return;
        }
        json(ex, 200, match.layoutJson(server));
    }

    /** 地图布局编辑：add/move/resize/remove/save/load/sectorNext/sectorPrev/rename/spawn。 */
    private static void mapEdit(HttpExchange ex) throws IOException {
        if (!auth(ex)) {
            json(ex, 401, "{\"ok\":false,\"msg\":\"未授权\"}");
            return;
        }
        String body = readBody(ex);
        String op = quoted(body, "op");
        String id = quoted(body, "id");
        double x = num(body, "x", 0);
        double z = num(body, "z", 0);
        double r = num(body, "r", 6);
        String name = quoted(body, "name");
        String sideS = quoted(body, "side");
        MinecraftServer server = BreakfrontServer.server();
        ServerMatch match = BreakfrontServer.match();
        if (server == null || match == null) {
            json(ex, 200, "{\"ok\":false,\"msg\":\"服务端未就绪\"}");
            return;
        }
        String msg;
        try {
            msg = switch (op == null ? "" : op) {
                case "add" -> match.editorAdd(server, x, z, r);
                case "move" -> id == null ? "缺少 id" : match.editorMove(server, id, x, z);
                case "resize" -> id == null ? "缺少 id" : match.editorResize(server, id, r);
                case "remove" -> id == null ? "缺少 id" : match.editorRemove(server, id);
                case "save" -> match.saveLayout();
                case "load" -> match.loadLayout(server);
                case "sectorNext" -> match.editorSectorNext(server);
                case "sectorPrev" -> match.editorSectorPrev(server);
                case "rename" -> match.editorSectorRename(server, name == null ? "" : name);
                case "spawn" -> sideS != null && sideS.equalsIgnoreCase("defender")
                        ? (match.setSpawnOverride(Side.DEFENDER, x, z) ? "守方出生点已设为 " + fmt(x) + "," + fmt(z) : "设置失败")
                        : (match.setSpawnOverride(Side.ATTACKER, x, z) ? "攻方出生点已设为 " + fmt(x) + "," + fmt(z) : "设置失败");
                default -> "未知操作: " + op;
            };
        } catch (Exception e) {
            msg = "执行失败: " + e;
        }
        Breakfront.LOGGER.info("[BF-Admin] mapedit {} -> {}", op, msg);
        json(ex, 200, "{\"ok\":true,\"msg\":\"" + esc(msg) + "\"}");
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }

    private static double num(String body, String key, double dflt) {
        int k = body.indexOf('"' + key + '"');
        if (k < 0) {
            return dflt;
        }
        int c = body.indexOf(':', k);
        if (c < 0) {
            return dflt;
        }
        int e = body.length();
        for (int i = c + 1; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == ',' || ch == '}' || ch == ' ') {
                e = i;
                break;
            }
        }
        try {
            return Double.parseDouble(body.substring(c + 1, e).trim());
        } catch (NumberFormatException ex2) {
            return dflt;
        }
    }

    private static void cmd(HttpExchange ex) throws IOException {
        if (!auth(ex)) {
            json(ex, 401, "{\"ok\":false,\"msg\":\"未授权\"}");
            return;
        }
        String body = readBody(ex);
        String name = quoted(body, "name");
        MinecraftServer server = BreakfrontServer.server();
        if (server == null) {
            json(ex, 200, "{\"ok\":false,\"msg\":\"服务端未就绪\"}");
            return;
        }
        String command = mapCmd(name);
        if (command == null) {
            json(ex, 200, "{\"ok\":false,\"msg\":\"未知动作: " + name + "\"}");
            return;
        }
        try {
            server.getCommandManager().executeWithPrefix(server.getCommandSource(), command);
            lastCmdResult = "ok: " + command;
            Breakfront.LOGGER.info("[BF-Admin] web cmd: {}", command);
            json(ex, 200, "{\"ok\":true,\"cmd\":\"" + esc(command) + "\"}");
        } catch (Exception e) {
            lastCmdResult = "fail: " + command + " (" + e + ")";
            json(ex, 200, "{\"ok\":false,\"msg\":\"" + esc(String.valueOf(e)) + "\"}");
        }
    }

    /** 动作名 → 原版命令（服务器控制台源，满权限执行）。 */
    private static String mapCmd(String name) {
        if (name == null) {
            return null;
        }
        return switch (name) {
            case "start" -> "bf start";
            case "end" -> "bf end";
            case "stop" -> "bf stop";
            case "fillOn" -> "bf fill on";
            case "fillOff" -> "bf fill off";
            case "autostartOn" -> "bf autostart on";
            case "autostartOff" -> "bf autostart off";
            case "mapOn" -> "bf map on";
            case "mapOff" -> "bf map off";
            case "statusText" -> "bf status";
            default -> null;
        };
    }

    // ================= 工具 =================

    private static String sectorDesc(BreakthroughGame g) {
        Sector s = g.currentSector();
        return s == null ? "-" : s.nameCn() + "（" + s.id() + "）";
    }

    private static String phaseCn(MatchPhase p) {
        return switch (p) {
            case LOBBY -> "大厅";
            case COUNTDOWN -> "倒计时";
            case BATTLE -> "战斗中";
            case ROUND_END -> "回合结算";
            default -> p.name();
        };
    }

    private static boolean auth(HttpExchange ex) {
        String q = ex.getRequestURI().getQuery();
        String token = q == null ? null : queryValue(q, "token");
        if (token == null) {
            return false;
        }
        Long at = TOKENS.get(token);
        if (at == null) {
            return false;
        }
        if (System.currentTimeMillis() - at > TTL_MS) {
            TOKENS.remove(token);
            return false;
        }
        TOKENS.put(token, System.currentTimeMillis()); // 滑动续期
        return true;
    }

    private static String randomToken() {
        byte[] b = new byte[18];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private static String readBody(HttpExchange ex) throws IOException {
        var in = ex.getRequestBody();
        byte[] buf = in.readAllBytes();
        return new String(buf, StandardCharsets.UTF_8);
    }

    private static void json(HttpExchange ex, int code, String body) throws IOException {
        respond(ex, code, body.getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8");
    }

    private static void respond(HttpExchange ex, int code, byte[] body, String ct) throws IOException {
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private static byte[] readResource(String path) {
        try (var in = WebAdminConsole.class.getResourceAsStream(path)) {
            if (in == null) {
                return "admin page missing".getBytes(StandardCharsets.UTF_8);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            return ("read err " + e).getBytes(StandardCharsets.UTF_8);
        }
    }

    /** 极简 JSON 取值（body 内 "key":"value" 形式，value 不含转义）。 */
    private static String quoted(String body, String key) {
        int k = body.indexOf('"' + key + '"');
        if (k < 0) {
            return null;
        }
        int c = body.indexOf(':', k);
        if (c < 0) {
            return null;
        }
        int q1 = body.indexOf('"', c);
        if (q1 < 0) {
            return null;
        }
        int q2 = body.indexOf('"', q1 + 1);
        if (q2 < 0) {
            return null;
        }
        return body.substring(q1 + 1, q2);
    }

    private static String queryValue(String query, String key) {
        for (String part : query.split("&")) {
            int e = part.indexOf('=');
            if (e > 0 && part.substring(0, e).equals(key)) {
                return part.substring(e + 1);
            }
        }
        return null;
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
