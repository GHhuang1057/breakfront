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
 * 默认直连公测服务器（mc.geekhonize.top，香港 frp 中继入口）；
 * 自建/本地测试服可通过编辑 config/breakfront-client.properties 的
 * host/port 覆盖，不影响正式客户端。客户端因此不需要再走原版「服务器选择」。
 */
public final class BfServerConfig {

    /** 公测服入口（frps 中继 → frpc → 香港 MC 主机）：换这里即可全网切换。 */
    public static final String DEFAULT_HOST = "mc.geekhonize.top";
    public static final int DEFAULT_PORT = 25565;
    public static final int DEFAULT_UPDATE_PORT = 25610;
    /**
     * 公测服更新/音乐通道（Cloudflare Worker geekhonize-bfupdate：自动取 GitHub 最新 dev release
     * 并代理下载）。未备案域名 + 直连 25610 会被 ICP 合规拦截，故正式通道统一走 CF。
     */
    public static final String DEFAULT_UPDATE_BASE = "https://bfupdate.geekhonize.top";

    private static final String FILE_NAME = "breakfront-client.properties";

    private static String host = DEFAULT_HOST;
    private static int port = DEFAULT_PORT;
    private static int updatePort = DEFAULT_UPDATE_PORT;
    /** 更新/音乐通道基址覆盖（如 https://bfupdate.geekhonize.top）；留空回退 http://host:updatePort。 */
    private static String updateBase = "";

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
                Files.writeString(file, String.join("\n",
                        "# BREAKFRONT 联机配置",
                        "# 默认即公测服 mc.geekhonize.top（内置），以下两行通常无需改动",
                        "# 自建/本地测试服请改 host（如 localhost）并保持端口一致",
                        "# updatebase：更新/音乐通道基址（公测服走 https://bfupdate.geekhonize.top 经 Cloudflare）；"
                                + "留空则回退 http://host:updateport",
                        "host=" + DEFAULT_HOST,
                        "port=" + DEFAULT_PORT,
                        "updatePort=" + DEFAULT_UPDATE_PORT) + "\n",
                        StandardCharsets.UTF_8);
            }
            host = kv.getOrDefault("host", DEFAULT_HOST);
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

    public static String address() {
        return host + ":" + port;
    }
}
