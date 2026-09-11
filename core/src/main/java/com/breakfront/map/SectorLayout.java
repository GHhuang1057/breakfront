package com.breakfront.map;

import com.breakfront.game.BreakthroughTuning;
import com.breakfront.game.Sector;
import com.breakfront.game.ZoneState;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 地图扇区配置（攻防推进表）：一组有序「扇区」，每个扇区含 1..N 个「据点」。
 *
 * 这是 /bfs 扇区编辑器产出、服务端启动时装载、/bfs apply 时应用到对局的
 * 唯一事实来源。持久化为 JSON（存于服务器运行目录 breakfront/sectors.json），
 * 取代此前硬编码在 ServerMatch 内的 viaduct 锚点坐标 —— 对 Metro 等外部导入地图，
 * 可直接在游戏里逐点划出来并保存，重启不再丢失。
 *
 * 数据结构（以 JSON 展示）：
 * <pre>
 * { "version": 1,
 *   "sectors": [
 *     { "name": "扇区一", "zones": [ {"id":"A1","x":20.5,"z":24.5,"radius":6.0}, ... ] },
 *     { "name": "扇区二", "zones": [ ... ] }
 *   ] }
 * </pre>
 *
 * XZ 平面圆形判定与 ZoneAnchor 一致；地表高度(Y)在应用/预览时按世界高度图实时求值。
 * 本类不依赖 Minecraft，仅依赖 MC 自带 Gson。
 */
public final class SectorLayout {

    public static final int VERSION = 1;
    public static final double DEFAULT_RADIUS = 6.0;

    private final List<SectorDef> sectors = new ArrayList<>();

    // 出生点（全图级，攻/守/大厅）：NaN 表示未设置，由 spawnFor 兜底到锚点/世界出生点。
    // 2026-09-11 新增：此前出生点只存在 server.properties，/bfs 编辑器无法编辑；
    // 现一并纳入布局，可经 /bfs spawn 编辑并随 sectors.json 持久化。
    private double attackerSpawnX = Double.NaN;
    private double attackerSpawnZ = Double.NaN;
    private double defenderSpawnX = Double.NaN;
    private double defenderSpawnZ = Double.NaN;
    private double lobbySpawnX = Double.NaN;
    private double lobbySpawnZ = Double.NaN;

    public SectorLayout() {
    }

    public SectorLayout(List<SectorDef> sectors) {
        if (sectors != null) {
            this.sectors.addAll(sectors);
        }
    }

    // ---------- 结构 ----------

    /** 一个扇区：名字 + 据点数个（顺序即占点顺序）。 */
    public static final class SectorDef {
        private String name;
        private final List<Zone> zones = new ArrayList<>();

        public SectorDef(String name) {
            this.name = name == null || name.isBlank() ? "扇区" : name;
        }

        public SectorDef(String name, List<Zone> zones) {
            this(name);
            if (zones != null) {
                this.zones.addAll(zones);
            }
        }

        public String name() {
            return name;
        }

        public void setName(String name) {
            if (name != null && !name.isBlank()) {
                this.name = name;
            }
        }

        /** 直接可变的据点列表（编辑器增删用）。 */
        public List<Zone> zones() {
            return zones;
        }

        public void addZone(Zone zone) {
            zones.add(zone);
        }

        public boolean removeZone(String zoneId) {
            return zones.removeIf(z -> z.id().equals(zoneId));
        }
    }

    /** 一个据点：XZ 圆心 + 半径（方块中心坐标）。 */
    public record Zone(String id, double x, double z, double radius) {
        public Zone {
            if (radius <= 0) {
                throw new IllegalArgumentException("zone radius must be > 0");
            }
        }
    }

    // ---------- 访问 ----------

    public List<SectorDef> sectors() {
        return sectors;
    }

    public int sectorCount() {
        return sectors.size();
    }

    public int zoneCount() {
        int n = 0;
        for (SectorDef def : sectors) {
            n += def.zones().size();
        }
        return n;
    }

    // ---------- 出生点（全图级） ----------

    public boolean hasAttackerSpawn() {
        return !Double.isNaN(attackerSpawnX);
    }

    public boolean hasDefenderSpawn() {
        return !Double.isNaN(defenderSpawnX);
    }

    public boolean hasLobbySpawn() {
        return !Double.isNaN(lobbySpawnX);
    }

    public double attackerSpawnX() {
        return attackerSpawnX;
    }

    public double attackerSpawnZ() {
        return attackerSpawnZ;
    }

    public double defenderSpawnX() {
        return defenderSpawnX;
    }

    public double defenderSpawnZ() {
        return defenderSpawnZ;
    }

    public double lobbySpawnX() {
        return lobbySpawnX;
    }

    public double lobbySpawnZ() {
        return lobbySpawnZ;
    }

    public void setAttackerSpawn(double x, double z) {
        attackerSpawnX = x;
        attackerSpawnZ = z;
    }

    public void setDefenderSpawn(double x, double z) {
        defenderSpawnX = x;
        defenderSpawnZ = z;
    }

    public void setLobbySpawn(double x, double z) {
        lobbySpawnX = x;
        lobbySpawnZ = z;
    }

    /** 转成纯逻辑对局扇区列表（服务端 BreakthroughGame 消费）。 */
    public List<Sector> toGameSectors() {
        double capture = BreakthroughTuning.DEFAULT_ZONE_CAPTURE_SECONDS;
        List<Sector> out = new ArrayList<>();
        int i = 1;
        for (SectorDef def : sectors) {
            List<ZoneState> zones = new ArrayList<>();
            for (Zone z : def.zones()) {
                zones.add(new ZoneState(z.id(), capture));
            }
            out.add(new Sector("s" + i, def.name(), zones));
            i++;
        }
        return out;
    }

    /** 文本概览（/bfs list 与调试用）。 */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        for (int si = 0; si < sectors.size(); si++) {
            SectorDef def = sectors.get(si);
            sb.append('\n').append(si + 1).append(". ").append(def.name());
            for (Zone z : def.zones()) {
                sb.append("\n    ").append(z.id())
                        .append(" @ (x=").append(String.format("%.1f", z.x()))
                        .append(", z=").append(String.format("%.1f", z.z()))
                        .append(", r=").append(String.format("%.0f", z.radius()))
                        .append(')');
            }
        }
        sb.append("\n出生点: ");
        sb.append(hasAttackerSpawn()
                ? String.format("攻(%.1f,%.1f) ", attackerSpawnX, attackerSpawnZ)
                : "攻(未设) ");
        sb.append(hasDefenderSpawn()
                ? String.format("守(%.1f,%.1f) ", defenderSpawnX, defenderSpawnZ)
                : "守(未设)");
        if (hasLobbySpawn()) {
            sb.append(String.format(" ｜ 大厅(%.1f,%.1f)", lobbySpawnX, lobbySpawnZ));
        }
        return sb.toString();
    }

    // ---------- JSON 序列化 ----------

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonArray secArr = new JsonArray();
        for (SectorDef def : sectors) {
            JsonObject so = new JsonObject();
            so.addProperty("name", def.name());
            JsonArray zArr = new JsonArray();
            for (Zone z : def.zones()) {
                JsonObject zo = new JsonObject();
                zo.addProperty("id", z.id());
                zo.addProperty("x", z.x());
                zo.addProperty("z", z.z());
                zo.addProperty("radius", z.radius());
                zArr.add(zo);
            }
            so.add("zones", zArr);
            secArr.add(so);
        }
        root.add("sectors", secArr);
        // 出生点（全图级）
        JsonObject sp = new JsonObject();
        if (hasAttackerSpawn()) {
            JsonObject a = new JsonObject();
            a.addProperty("x", attackerSpawnX);
            a.addProperty("z", attackerSpawnZ);
            sp.add("attacker", a);
        }
        if (hasDefenderSpawn()) {
            JsonObject d = new JsonObject();
            d.addProperty("x", defenderSpawnX);
            d.addProperty("z", defenderSpawnZ);
            sp.add("defender", d);
        }
        if (hasLobbySpawn()) {
            JsonObject l = new JsonObject();
            l.addProperty("x", lobbySpawnX);
            l.addProperty("z", lobbySpawnZ);
            sp.add("lobby", l);
        }
        if (sp.size() > 0) {
            root.add("spawns", sp);
        }
        return new Gson().toJson(root);
    }

    /** 解析 JSON；格式损坏时抛 IllegalArgumentException。 */
    public static SectorLayout parse(String json) {
        JsonElement el;
        try {
            el = JsonParser.parseString(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 解析失败: " + e.getMessage());
        }
        if (el == null || !el.isJsonObject()) {
            throw new IllegalArgumentException("顶层必须是 JSON 对象");
        }
        JsonObject root = el.getAsJsonObject();
        SectorLayout layout = new SectorLayout();
        if (root.has("sectors") && root.get("sectors").isJsonArray()) {
            for (JsonElement se : root.getAsJsonArray("sectors")) {
                if (!se.isJsonObject()) {
                    continue;
                }
                JsonObject so = se.getAsJsonObject();
                String name = so.has("name") ? so.get("name").getAsString() : null;
                SectorDef def = new SectorDef(name);
                if (so.has("zones") && so.get("zones").isJsonArray()) {
                    for (JsonElement ze : so.getAsJsonArray("zones")) {
                        if (!ze.isJsonObject()) {
                            continue;
                        }
                        JsonObject zo = ze.getAsJsonObject();
                        if (!zo.has("id") || !zo.has("x") || !zo.has("z")) {
                            continue;
                        }
                        String id = zo.get("id").getAsString();
                        double x = zo.get("x").getAsDouble();
                        double z = zo.get("z").getAsDouble();
                        double r = zo.has("radius") ? zo.get("radius").getAsDouble() : DEFAULT_RADIUS;
                        def.addZone(new Zone(id, x, z, Math.max(1.0, r)));
                    }
                }
                if (!def.zones().isEmpty()) {
                    layout.sectors.add(def);
                }
            }
        }
        // 出生点（全图级）
        if (root.has("spawns") && root.get("spawns").isJsonObject()) {
            JsonObject sp = root.getAsJsonObject("spawns");
            if (sp.has("attacker") && sp.get("attacker").isJsonObject()) {
                JsonObject a = sp.getAsJsonObject("attacker");
                if (a.has("x") && a.has("z")) {
                    layout.setAttackerSpawn(a.get("x").getAsDouble(), a.get("z").getAsDouble());
                }
            }
            if (sp.has("defender") && sp.get("defender").isJsonObject()) {
                JsonObject d = sp.getAsJsonObject("defender");
                if (d.has("x") && d.has("z")) {
                    layout.setDefenderSpawn(d.get("x").getAsDouble(), d.get("z").getAsDouble());
                }
            }
            if (sp.has("lobby") && sp.get("lobby").isJsonObject()) {
                JsonObject l = sp.getAsJsonObject("lobby");
                if (l.has("x") && l.has("z")) {
                    layout.setLobbySpawn(l.get("x").getAsDouble(), l.get("z").getAsDouble());
                }
            }
        }
        return layout;
    }

    // ---------- 内置默认：viaduct 自建城（无配置文件时的兜底） ----------

    public static SectorLayout defaultViaduct() {
        return new SectorLayout(List.of(
                new SectorDef("扇区一", List.of(
                        new Zone("A1", 20.5, 24.5, 6.0),
                        new Zone("A2", 76.5, 24.5, 6.0))),
                new SectorDef("扇区二", List.of(
                        new Zone("B1", 43.5, 51.5, 6.0)))));
    }
}
