package com.breakfront.client.bf;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * BREAKFRONT 联机服务器配置。
 *
 * 默认直连公测服务器（play.geekhonize.top，北京 frps 中继入口）；
 * 自建/本地测试服可通过编辑 config/breakfront-client.properties 的
 * host/port 覆盖，不影响正式客户端。客户端因此不需要再走原版「服务器选择」。
 */
public final class BfServerConfig {

    /** 公测服入口（frps 中继 → frpc → 香港 MC 主机）：换这里即可全网切换。 */
    public static final String DEFAULT_HOST = "play.geekhonize.top";
    public static final int DEFAULT_PORT = 25565;
    public static final int DEFAULT_UPDATE_PORT = 25610;
    /** 2026-09-10 前的旧公测域名（已改为官网）。老客户端把它写进了配置文件，读到必须迁移。 */
    private static final String LEGACY_DEFAULT_HOST = "mc.geekhonize.top";
    /**
     * 公测服更新/音乐通道（Cloudflare Worker geekhonize-bfupdate：自动取 GitHub 最新 dev release
     * 并代理下载）。未备案域名 + 直连 25610 会被 ICP 合规拦截，故正式通道统一走 CF。
     */
    public static final String DEFAULT_UPDATE_BASE = "https://bfupdate.geekhonize.top";

    /**
     * 公测服**音乐库**入口（2026-09-10 定案）。
     *
     * <p>音乐不从 CF 走：Cloudflare Workers 的出站 fetch 有**端口白名单**
     * （80/8080/443/8443/2052… ），25610 不在其中 → Worker 回源被 CF 以
     * error 1003 拒绝。故音乐改为客户端直连 MC 主机的更新服务端口。
     * 用 IP 而非域名：未备案域名直连易被 ICP 合规拦截，IP 更稳。
     *
     * <p>若将来让音乐也经 CF 分发，两条路：① 把音乐作为 GitHub Release 资产
     * （Worker 已能代理 443，客户端零改动）；② 在 frpc 增加一条映射到 CF 白名单
     * 端口（如北京 8080），再把此处换成对应地址。
     */
    public static final String DEFAULT_MUSIC_BASE = "http://8.141.114.60:25610";

    private static final String FILE_NAME = "breakfront-client.properties";

    private static String host = DEFAULT_HOST;
    private static int port = DEFAULT_PORT;
    private static int updatePort = DEFAULT_UPDATE_PORT;
    /** 更新/音乐通道基址覆盖（如 https://bfupdate.geekhonize.top）；留空回退 http://host:updatePort。 */
    private static String updateBase = "";
    /** 音乐库基址覆盖；留空按 musicBase() 规则推导。 */
    private static String musicBase = "";

    private BfServerConfig() {
    }

    public static void load() {
        try {
            Path file = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
            Map<String, String> kv = new HashMap<>();
            if (Files.isRegularFile(file)) {
                for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        kv.put(line.substring(0, eq).trim().toLowerCase(), line.substring(eq + 1).trim());
                    }
                }
            } else {
                Files.createDirectories(file.getParent());
                Files.writeString(file, templateText(), StandardCharsets.UTF_8);
            }
            host = kv.getOrDefault("host", DEFAULT_HOST);
            // ⚠️ 旧默认域名迁移：老客户端首次运行时把当时的 DEFAULT_HOST（mc.）写进了配置。
            // 若不迁移，换域名后 host != 新 DEFAULT_HOST → 被判为「自建服」→
            // 更新源回退 http://mc...:25610 → 打到 Cloudflare 边缘超时（表现即「更新源离线」）。
            if (LEGACY_DEFAULT_HOST.equalsIgnoreCase(host)) {
                host = DEFAULT_HOST;
                Files.writeString(file, templateText(), StandardCharsets.UTF_8);
            }
            port = parseIntSafe(kv.get("port"), DEFAULT_PORT);
            updatePort = parseIntSafe(kv.get("updateport"), DEFAULT_UPDATE_PORT);
            String ub = kv.getOrDefault("updatebase", "");
            if (ub == null) {
                ub = "";
            }
            updateBase = ub.trim();
            if (!updateBase.isEmpty() && updateBase.endsWith("/")) {
                updateBase = updateBase.substring(0, updateBase.length() - 1);
            }
            String mb = kv.getOrDefault("musicbase", "");
            if (mb == null) {
                mb = "";
            }
            musicBase = mb.trim();
            if (!musicBase.isEmpty() && musicBase.endsWith("/")) {
                musicBase = musicBase.substring(0, musicBase.length() - 1);
            }
        } catch (IOException e) {
            // 保持默认
        }
    }

    private static int parseIntSafe(String v, int def) {
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 首次生成/迁移时写回的配置模板（host 等取当前默认值）。 */
    private static String templateText() {
        return String.join("\n",
                "# BREAKFRONT 联机配置",
                "# 默认即公测服 play.geekhonize.top（内置），以下两行通常无需改动",
                "# 自建/本地测试服请改 host（如 localhost）并保持端口一致",
                "# updatebase：更新通道基址（公测服走 https://bfupdate.geekhonize.top 经 Cloudflare）；"
                        + "留空则回退 http://host:updateport",
                "# musicbase：音乐库基址（公测服直连 MC 主机音频服务；Cloudflare Workers 出站"
                        + "端口白名单不含 25610 故音乐不经 CF）；留空按上述规则推导",
                "host=" + DEFAULT_HOST,
                "port=" + DEFAULT_PORT,
                "updatePort=" + DEFAULT_UPDATE_PORT) + "\n";
    }

    public static String host() {
        return host;
    }

    public static int port() {
        return port;
    }

    public static int updatePort() {
        return updatePort;
    }

    /**
     * 更新/音乐通道基址：配置 updatebase（如 https://bfupdate.geekhonize.top）时优先，
     * 否则回退 http://host:updatePort（本地/自建服场景）。
     * 说明：公测服走未备案域名 + HTTP 会被 ICP 合规拦截，正式通道改由 Cloudflare 前端转发。
     */
    public static String updateBase() {
        if (!updateBase.isEmpty()) {
            return updateBase;                       // 显式配置优先
        }
        if (!DEFAULT_HOST.equalsIgnoreCase(host)) {
            return "http://" + host + ":" + updatePort; // 自建/本地服：就地取服务端更新源
        }
        return DEFAULT_UPDATE_BASE;                   // 公测服：Cloudflare 更新通道
    }

    /**
     * 音乐库基址。与 {@link #updateBase()} 分离的原因：CF Workers 出站 fetch 有端口白名单，
     * 25610 不在其中，音乐无法经 Worker 回源（详见 {@link #DEFAULT_MUSIC_BASE}）。
     *
     * <p>推导顺序：显式配置 musicbase > 自建服（http://host:updatePort）> 公测服直连 MC 主机。
     */
    public static String musicBase() {
        if (!musicBase.isEmpty()) {
            return musicBase;                         // 显式配置优先
        }
        if (!DEFAULT_HOST.equalsIgnoreCase(host)) {
            return "http://" + host + ":" + updatePort; // 自建/本地服：与更新源同一入口
        }
        return DEFAULT_MUSIC_BASE;                    // 公测服：直连 MC 主机音频服务
    }

    public static String address() {
        return host + ":" + port;
    }
}
