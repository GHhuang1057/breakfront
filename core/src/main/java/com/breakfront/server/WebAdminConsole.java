package com.breakfront.server;

import com.breakfront.Breakfront;
import com.breakfront.game.BreakthroughGame;
import com.breakfront.game.MatchPhase;
import com.breakfront.game.Sector;
import com.breakfront.game.Side;
import com.breakfront.game.ZoneState;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.Base64;

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
    private static final long TTL_MS = 12L * 60 * 60 * 1000; // 12h：热更/重启不打断浏览器会话
    /** 会话持久化文件（runDir/breakfront/webtokens.txt）：重启/热更后恢复，浏览器不再 401。 */
    private static volatile java.nio.file.Path tokenFile;
    private static volatile String lastCmdResult = "";

    // ---- 地形底图采样缓存（/bfadmin/api/mapterrain，90s 静态缓存）----
    private static final Object TERRAIN_LOCK = new Object();
    private static volatile long terrainCachedAt = 0;
    private static volatile String terrainKey = "";
    private static volatile byte[] terrainBytes = null;
    private static volatile int terrainW = 0, terrainH = 0, terrainStep = 0, terrainX0 = 0, terrainZ0 = 0;
    /** 底色 / 默认色（无数据或未知方块）：低饱和蓝灰 55697E。 */
    private static final int[] TERRAIN_BASE = {0x55, 0x69, 0x7E};
    /**
     * 未生成/未加载区块的颜色（2026-09-10）。管理台采样的是无人在场的坐标，那里
     * 常常从未生成过地形，此时 {@code getTopY} 返回世界底部 —— 若与"已加载但无方块"
     * 同色，整幅底图就是一片纯色（表现即"什么都看不到"）。
     * 特意选偏红棕色：与页面背景(#06080c)、常规底色(#55697E 系)都能一眼区分。
     */
    private static final int[] TERRAIN_UNLOADED = {0x3A, 0x24, 0x22};
    /** 海拔亮度参考：topY 映射到 0.6~1.15（低海拔暗、高海拔亮）。 */
    private static final double TERRAIN_REF_LOW = -64.0, TERRAIN_REF_SPAN = 30.0;

    private WebAdminConsole() {
    }

    /** 由 ModUpdateServer 启动时挂接持久化路径（runDir 下）。 */
    public static void attachTokenFile(java.nio.file.Path f) {
        tokenFile = f;
        loadTokens();
    }

    private static void loadTokens() {
        if (tokenFile == null || !java.nio.file.Files.isRegularFile(tokenFile)) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            for (String line : java.nio.file.Files.readAllLines(tokenFile)) {
                int tab = line.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                String tok = line.substring(0, tab);
                try {
                    long at = Long.parseLong(line.substring(tab + 1));
                    if (now - at <= TTL_MS) {
                        TOKENS.put(tok, at);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            Breakfront.LOGGER.info("[BF-Admin] restored {} admin tokens", TOKENS.size());
        } catch (Exception e) {
            Breakfront.LOGGER.warn("[BF-Admin] token load failed: {}", e.toString());
        }
    }

    private static void saveTokens() {
        if (tokenFile == null || TOKENS.isEmpty()) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            long now = System.currentTimeMillis();
            for (var e : TOKENS.entrySet()) {
                if (now - e.getValue() <= TTL_MS) {
                    sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
                }
            }
            if (tokenFile.getParent() != null) {
                java.nio.file.Files.createDirectories(tokenFile.getParent());
            }
            java.nio.file.Files.writeString(tokenFile, sb.toString());
        } catch (Exception e) {
            Breakfront.LOGGER.warn("[BF-Admin] token save failed: {}", e.toString());
        }
    }

    /** ModUpdateServer 的 /bfadmin/* context 入口。 */
    public static void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String m = ex.getRequestMethod();
            if (path.equals("/bfadmin/api/login") && m.equalsIgnoreCase("POST")) {
                login(ex);
            } else if (path.equals("/bfadmin/api/authlogin") && m.equalsIgnoreCase("POST")) {
                authLogin(ex);
            } else if (path.equals("/bfadmin/api/status") && m.equalsIgnoreCase("GET")) {
                status(ex);
            } else if (path.equals("/bfadmin/api/map") && m.equalsIgnoreCase("GET")) {
                map(ex);
            } else if (path.equals("/bfadmin/api/mapterrain") && m.equalsIgnoreCase("GET")) {
                mapTerrain(ex);
            } else if (path.equals("/bfadmin/api/worldinfo") && m.equalsIgnoreCase("GET")) {
                worldInfo(ex);
            } else if (path.startsWith("/bfadmin/api/sqm/") && m.equalsIgnoreCase("GET")) {
                sqmProxy(ex, path.substring("/bfadmin/api/sqm/".length()));
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

    /** Geekhonize 账号登录（管理台代理）：Auth /login → roles 含 admin 才发管理会话。 */
    private static void authLogin(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        Map<String, Object> o;
        try {
            o = Json.parseObject(body);
        } catch (Json.JsonException e) {
            json(ex, 400, "{\"ok\":false,\"msg\":\"请求体解析失败\"}");
            return;
        }
        String user = Json.str(o, "username");
        String pw = Json.str(o, "password");
        if (user == null || pw == null) {
            json(ex, 400, "{\"ok\":false,\"msg\":\"缺少用户名或密码\"}");
            return;
        }
        AuthBridge.LoginResult lr = AuthBridge.login(user, pw);
        if (!lr.ok() || !lr.roles().contains("admin")) {
            json(ex, 200, "{\"ok\":false,\"msg\":\"" + (lr.ok()
                    ? "该账号无管理权限（需 admin 角色）" : "登录失败：账号或密码错误") + "\"}");
            return;
        }
        String token = randomToken();
        TOKENS.put(token, System.currentTimeMillis());
        saveTokens();
        json(ex, 200, "{\"ok\":true,\"token\":\"" + token
                + "\",\"msg\":\"Geekhonize 管理员 " + esc(lr.username()) + " 已登录\"}");
    }

    private static void login(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        String pw;
        try {
            pw = Json.str(Json.parseObject(body), "pw");
        } catch (Json.JsonException e) {
            pw = null;
        }
        boolean ok = pw != null && AdminService.password != null && pw.equals(AdminService.password);
        String token = null;
        if (ok) {
            token = randomToken();
            TOKENS.put(token, System.currentTimeMillis());
            saveTokens();
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
        // AI：假玩家小队（含大厅「非战斗 BOT」与真人热顶替计数）
        BotSquad bots = match.bots();
        sb.append("\"aiAlive\":").append(bots == null ? 0 : bots.alive()).append(',');
        sb.append("\"aiTakeover\":").append(bots == null ? 0 : bots.humansEngaged()).append(',');
        sb.append("\"aiInfo\":\"").append(esc(bots == null ? "-" : bots.info())).append("\",");
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

    /** 地形底图采样（管理台俯瞰画布底）：低清方块色网格，90s 缓存。 */
    private static void mapTerrain(HttpExchange ex) throws IOException {
        if (!auth(ex)) {
            json(ex, 401, "{\"ok\":false,\"msg\":\"未授权\"}");
            return;
        }
        MinecraftServer server = BreakfrontServer.server();
        if (server == null) {
            json(ex, 200, "{\"ok\":false,\"msg\":\"服务端未就绪\"}");
            return;
        }
        String q = ex.getRequestURI().getQuery();
        double cx = dq(q, "cx", 0);
        double cz = dq(q, "cz", 0);
        // 地图跨度可达数千米，故 half 上限放宽到 1024；分辨率不足时自动加粗 step
        // （像素数封顶 160×160：既控制响应体大小，也控制采样耗时）。
        double half = Math.max(16, Math.min(1024, dq(q, "half", 96)));
        int step = Math.max(1, Math.min(64, (int) Math.round(dq(q, "step", 2))));
        int n = (int) Math.floor(2 * half / step);
        if (n > 160) {
            step = (int) Math.ceil(2 * half / 160.0);
            n = (int) Math.floor(2 * half / step);
        }
        String key = ((int) cx) + "," + ((int) cz) + "," + half + "," + step;
        synchronized (TERRAIN_LOCK) {
            long now = System.currentTimeMillis();
            if (terrainKey.equals(key) && now - terrainCachedAt < 90000) {
                respondTerrain(ex);
                return;
            }
            ServerWorld world = server.getOverworld();
            int x0 = (int) Math.floor(cx - half);
            int z0 = (int) Math.floor(cz - half);
            int x1 = x0 + n * step;
            int z1 = z0 + n * step;
            // 关键修复：先按需载入范围内**已存在**的区块（create=false，不触发生成）。
            // 否则无人在场的坐标一片空白 —— 这正是"底图什么都看不到"的根因。
            preloadExistingChunks(world, x0, z0, x1, z1);
            int bottom = world.getBottomY();
            byte[] rgb = new byte[n * n * 3];
            int p = 0;
            for (int row = 0; row < n; row++) {
                int bz = z0 + row * step;
                for (int col = 0; col < n; col++) {
                    int bx = x0 + col * step;
                    if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
                        // 未加载：明确标出，便于运营判断需要预热的区域
                        rgb[p++] = (byte) TERRAIN_UNLOADED[0];
                        rgb[p++] = (byte) TERRAIN_UNLOADED[1];
                        rgb[p++] = (byte) TERRAIN_UNLOADED[2];
                        continue;
                    }
                    int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING, bx, bz);
                    if (top <= bottom) {
                        top = world.getTopY(Heightmap.Type.WORLD_SURFACE, bx, bz);
                    }
                    if (top <= bottom) {
                        // 该列无任何方块 = 地形从未生成（或虚空）。
                        // 实测证据：出生点外 2km 的坐标采样结果与"已加载但无方块"完全同色，
                        // 说明 isChunkLoaded 对未生成区块同样可能返回 true —— 因此必须在
                        // 这里兜底着色，否则「未生成」会被误读成「有地形但无方块」。
                        rgb[p++] = (byte) TERRAIN_UNLOADED[0];
                        rgb[p++] = (byte) TERRAIN_UNLOADED[1];
                        rgb[p++] = (byte) TERRAIN_UNLOADED[2];
                        continue;
                    }
                    BlockState bs = world.getBlockState(new BlockPos(bx, top, bz));
                    double factor = 0.6 + 0.55 * Math.max(0.0, Math.min(1.0,
                            (top - TERRAIN_REF_LOW) / TERRAIN_REF_SPAN));
                    int[] c = bs == null ? TERRAIN_BASE : terrainPalette(bs);
                    rgb[p++] = (byte) Math.min(255, (int) (c[0] * factor));
                    rgb[p++] = (byte) Math.min(255, (int) (c[1] * factor));
                    rgb[p++] = (byte) Math.min(255, (int) (c[2] * factor));
                }
            }
            terrainKey = key;
            terrainCachedAt = now;
            terrainBytes = rgb;
            terrainW = terrainH = n;
            terrainStep = step;
            terrainX0 = x0;
            terrainZ0 = z0;
            respondTerrain(ex);
        }
    }

    /** 区块预加载上限：视野拉到千米级时区块数以万计，逐个载入会长时间占住服务器线程。 */
    private static final int PRELOAD_CHUNK_CAP = 4096;

    /**
     * 世界概况（2026-09-10）：出生点 + **已生成地形**的范围。
     *
     * <p>动机：地图编辑器的据点坐标可能落在「从未生成过区块」的坐标上（换图后坐标未同步
     * 是最常见的情形）。此时俯瞰底图整片显示为未生成色，看起来像功能坏掉。
     * 这里扫描存档的 region 文件（r.X.Z.mca，每个覆盖 32×32 区块 = 512×512 格）
     * 得出世界实际已生成的范围，让运营一眼看出据点该划在哪、或需先去哪些区域。
     */
    private static void worldInfo(HttpExchange ex) throws IOException {
        if (!auth(ex)) {
            json(ex, 401, "{\"ok\":false,\"msg\":\"未授权\"}");
            return;
        }
        MinecraftServer server = BreakfrontServer.server();
        if (server == null) {
            json(ex, 200, "{\"ok\":false,\"msg\":\"服务端未就绪\"}");
            return;
        }
        ServerWorld world = server.getOverworld();
        var spawnPos = world.getSpawnPos();
        StringBuilder sb = new StringBuilder("{\"ok\":true");
        sb.append(",\"spawn\":{\"x\":").append(spawnPos.getX())
                .append(",\"z\":").append(spawnPos.getZ()).append('}');
        long rx0 = Long.MAX_VALUE;
        long rx1 = Long.MIN_VALUE;
        long rz0 = Long.MAX_VALUE;
        long rz1 = Long.MIN_VALUE;
        int regions = 0;
        try {
            // 注意：yarn 1.21.1 的 WorldSavePath 没有 REGIONS 常量（已核官方 API 文档），
            // 主世界的 region 目录固定在存档根之下：<save>/region/
            java.nio.file.Path dir = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
                    .resolve("region");
            if (java.nio.file.Files.isDirectory(dir)) {
                try (var stream = java.nio.file.Files.list(dir)) {
                    for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) stream::iterator) {
                        String n = f.getFileName().toString();
                        if (!n.startsWith("r.") || !n.endsWith(".mca")) {
                            continue;
                        }
                        String[] parts = n.substring(2, n.length() - 4).split("\\.");
                        if (parts.length != 2) {
                            continue;
                        }
                        try {
                            long rx = Long.parseLong(parts[0]);
                            long rz = Long.parseLong(parts[1]);
                            rx0 = Math.min(rx0, rx);
                            rx1 = Math.max(rx1, rx);
                            rz0 = Math.min(rz0, rz);
                            rz1 = Math.max(rz1, rz);
                            regions++;
                        } catch (NumberFormatException ignored) {
                            // 非法文件名跳过
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Breakfront.LOGGER.warn("[BF-Admin] region 扫描失败: {}", t.toString());
        }
        if (regions > 0) {
            sb.append(",\"generated\":{\"x0\":").append(rx0 * 512)
                    .append(",\"z0\":").append(rz0 * 512)
                    .append(",\"x1\":").append((rx1 + 1) * 512)
                    .append(",\"z1\":").append((rz1 + 1) * 512)
                    .append(",\"regions\":").append(regions).append('}');
        } else {
            sb.append(",\"generated\":null");
        }
        sb.append('}');
        json(ex, 200, sb.toString());
    }

    /**
     * 载入范围内**已存在**的区块（不触发生成）。
     *
     * <p>管理台采样点通常无人在场，区块未加载时 {@code getTopY} 一律返回世界底部，
     * 底图退化为纯色。这里以 {@code create=false} 只把**磁盘上已有**的区块读入内存，
     * 不会在管理台操作时生成新地形（避免卡服/写盘）。
     */
    private static void preloadExistingChunks(ServerWorld world, int x0, int z0, int x1, int z1) {
        int cx0 = x0 >> 4;
        int cz0 = z0 >> 4;
        int cx1 = x1 >> 4;
        int cz1 = z1 >> 4;
        long total = (long) (cx1 - cx0 + 1) * (cz1 - cz0 + 1);
        if (total <= 0 || total > PRELOAD_CHUNK_CAP) {
            return;
        }
        var cm = world.getChunkManager();
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (world.isChunkLoaded(cx, cz)) {
                    continue;
                }
                try {
                    cm.getChunk(cx, cz, net.minecraft.world.chunk.ChunkStatus.FULL, false);
                } catch (Throwable ignored) {
                    // 单个区块失败不影响整幅底图
                }
            }
        }
    }

    private static void respondTerrain(HttpExchange ex) throws IOException {
        String b64 = Base64.getEncoder().encodeToString(terrainBytes);
        json(ex, 200, "{\"ok\":true,\"x0\":" + terrainX0 + ",\"z0\":" + terrainZ0
                + ",\"step\":" + terrainStep + ",\"w\":" + terrainW + ",\"h\":" + terrainH
                + ",\"b64\":\"" + b64 + "\"}");
    }

    private static int[] terrainPalette(BlockState bs) {
        String id = Registries.BLOCK.getId(bs.getBlock()).getPath();
        if (id.contains("water")) {
            return new int[]{0x3A, 0x74, 0xB4};
        }
        if (id.contains("lava")) {
            return new int[]{0xB0, 0x53, 0x28};
        }
        if (id.contains("sand")) {
            return new int[]{0xBF, 0xB6, 0x8C};
        }
        // 泥土系（含 coarse_dirt / rooted_dirt / podzol / mud / farmland / dirt_path）：
        // 这是最常见的自然地表之一，此前漏配 → 大片区域退化成默认底色，看起来像"没地形"。
        if (id.contains("dirt") || id.contains("podzol") || id.contains("mud")
                || id.contains("farmland") || id.contains("path") || id.contains("soil")) {
            return new int[]{0x8B, 0x6F, 0x4E};
        }
        if (id.contains("grass") || id.contains("moss") || id.contains("mycel")) {
            return new int[]{0x6F, 0x9E, 0x68};
        }
        if (id.contains("snow") || id.contains("powder")) {
            return new int[]{0xC9, 0xD2, 0xDC};
        }
        if (id.contains("deepslate") || id.contains("blackstone")) {
            return new int[]{0x4E, 0x52, 0x5C};
        }
        if (id.contains("stone") || id.contains("tuff") || id.contains("calcite")
                || id.contains("granite") || id.contains("diorite") || id.contains("andesite")) {
            return new int[]{0x8A, 0x8F, 0x9A};
        }
        if (id.contains("cobble") || id.contains("gravel")) {
            return new int[]{0x7A, 0x7E, 0x8A};
        }
        if (id.contains("brick") || id.contains("terracotta") || id.contains("red_sandstone")) {
            return new int[]{0xA2, 0x64, 0x5A};
        }
        if (id.contains("plank") || id.contains("log") || id.contains("wood")) {
            return new int[]{0x92, 0x76, 0x4F};
        }
        if (id.contains("leaves")) {
            return new int[]{0x4A, 0x7A, 0x4E};
        }
        if (id.contains("concrete")) {
            return new int[]{0xA8, 0xAF, 0xBA};
        }
        if (id.contains("glass") || id.contains("ice")) {
            return new int[]{0x86, 0xAF, 0xC8};
        }
        if (id.contains("rail") || id.contains("copper")) {
            return new int[]{0x8F, 0x6A, 0x48};
        }
        if (id.contains("clay")) {
            return new int[]{0x9A, 0xA3, 0xAC};
        }
        if (id.contains("obsidian")) {
            return new int[]{0x34, 0x36, 0x3E};
        }
        return TERRAIN_BASE;
    }

    /** query 数值读取。 */
    private static double dq(String q, String key, double dflt) {
        String v = queryValue(q == null ? "" : q, key);
        if (v == null) {
            return dflt;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** 地图布局编辑：add/move/resize/remove/save/load/sectorNext/sectorPrev/rename/spawn。 */
    private static void mapEdit(HttpExchange ex) throws IOException {
        if (!auth(ex)) {
            json(ex, 401, "{\"ok\":false,\"msg\":\"未授权\"}");
            return;
        }
        String body = readBody(ex);
        // 2026-09-10：改用真正的 JSON 解析（原 indexOf 键匹配在出现同名键/平铺+嵌套时会取错字段，
        // 是地图编辑器失效的地基性缺陷）。兼容两种载荷：平铺顶层字段，或 {payload:{...}} 嵌套。
        Map<String, Object> root;
        try {
            root = Json.parseObject(body);
        } catch (Json.JsonException e) {
            json(ex, 400, "{\"ok\":false,\"msg\":\"请求体解析失败：" + esc(e.getMessage()) + "\"}");
            return;
        }
        Map<String, Object> p = Json.obj(root, "payload");
        final Map<String, Object> f = p != null ? p : root;

        String op = firstNonNull(Json.str(root, "op"), Json.str(f, "op"));
        // id 优先取 payload 内的（嵌套载荷更精确），再回落顶层
        String id = firstNonNull(Json.str(f, "id"), Json.str(root, "id"));
        double x = Json.dbl(f, "x", Json.dbl(root, "x", 0));
        double z = Json.dbl(f, "z", Json.dbl(root, "z", 0));
        double r = Json.dbl(f, "r", Json.dbl(root, "r", 6));
        String name = firstNonNull(Json.str(f, "name"), Json.str(root, "name"));
        String sideS = firstNonNull(Json.str(f, "side"), Json.str(root, "side"));
        MinecraftServer server = BreakfrontServer.server();
        ServerMatch match = BreakfrontServer.match();
        if (server == null || match == null) {
            json(ex, 200, "{\"ok\":false,\"msg\":\"服务端未就绪\"}");
            return;
        }
        String msg;
        boolean ok = true;
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
                default -> {
                    ok = false;
                    yield "未知操作: " + op;
                }
            };
        } catch (Exception e) {
            ok = false;
            msg = "执行失败: " + e;
        }
        // 操作失败时把 ok 置 false，前端据此提示（原先恒为 true，错误被吞）
        if (msg != null && (msg.startsWith("缺少") || msg.startsWith("未知") || msg.startsWith("执行失败")
                || msg.endsWith("失败") || msg.contains("失败："))) {
            ok = false;
        }
        Breakfront.LOGGER.info("[BF-Admin] mapedit {} -> {}", op, msg);
        json(ex, 200, "{\"ok\":" + ok + ",\"msg\":\"" + esc(msg) + "\"}");
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }

    private static void cmd(HttpExchange ex) throws IOException {
        if (!auth(ex)) {
            json(ex, 401, "{\"ok\":false,\"msg\":\"未授权\"}");
            return;
        }
        String body = readBody(ex);
        String name;
        try {
            name = Json.str(Json.parseObject(body), "name");
        } catch (Json.JsonException e) {
            json(ex, 400, "{\"ok\":false,\"msg\":\"请求体解析失败\"}");
            return;
        }
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
            // 兼容 header 方式（前端统一 Authorization: Bearer）
            String h = ex.getRequestHeaders().getFirst("Authorization");
            if (h != null && h.startsWith("Bearer ")) {
                token = h.substring("Bearer ".length()).trim();
            }
        }
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

    // ================= squaremap 真实俯瞰图代理 =================

    /** squaremap 内置 Web 服务的本机地址（默认 8080；可用 -Dbreakfront.squaremap 覆盖）。 */
    private static final String SQM_BASE =
            System.getProperty("breakfront.squaremap", "http://127.0.0.1:8080");
    /** 同一进程复用连接池；瓦片很小，4s 连接超时足够。 */
    private static final java.net.http.HttpClient SQM_HTTP = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(4))
            .build();

    /**
     * 把 {@code /bfadmin/api/sqm/<sub>} 透明代理到 squaremap 的内置 Web 服务。
     *
     * <p>这样管理台只需暴露 25610 一个入口：浏览器拿 squaremap 渲染的**真实俯视瓦片**
     * （{@code tiles/<world>/<z>/<x>_<y>.png}）与 {@code tiles/settings.json}（世界/缩放元数据），
     * 无需把 squaremap 的 8080 端口暴露到公网。
     */
    private static void sqmProxy(HttpExchange ex, String sub) throws IOException {
        if (!auth(ex)) {
            json(ex, 401, "{\"ok\":false,\"msg\":\"未授权或已过期\"}");
            return;
        }
        if (sub.isEmpty() || sub.contains("..") || !sub.matches("[A-Za-z0-9_./-]+")) {
            respond(ex, 400, "bad path".getBytes(StandardCharsets.UTF_8), "text/plain");
            return;
        }
        try {
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(SQM_BASE + "/" + sub))
                    .timeout(java.time.Duration.ofSeconds(10)).GET().build();
            var resp = SQM_HTTP.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            int code = resp.statusCode();
            if (code != 200) {
                respond(ex, code == 404 ? 404 : 502, new byte[0], "text/plain");
                return;
            }
            String ct = resp.headers().firstValue("content-type").orElse(sqmContentType(sub));
            ex.getResponseHeaders().set("Content-Type", ct);
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=20");
            ex.sendResponseHeaders(200, resp.body().length);
            ex.getResponseBody().write(resp.body());
            ex.close();
        } catch (Exception e) {
            json(ex, 502, "{\"ok\":false,\"msg\":\"squaremap 不可达（未安装/未启动/未渲染）："
                    + esc(e.getClass().getSimpleName()) + "\"}");
        }
    }

    private static String sqmContentType(String sub) {
        if (sub.endsWith(".png")) {
            return "image/png";
        }
        if (sub.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (sub.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
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

    /** 极简 JSON 取值（仅登录等极简场景；复杂解析统一走 {@link Json}）。 */
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
