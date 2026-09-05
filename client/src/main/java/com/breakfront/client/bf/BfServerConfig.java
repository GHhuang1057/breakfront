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

    private static final String FILE_NAME = "breakfront-client.properties";

    private static String host = DEFAULT_HOST;
    private static int port = DEFAULT_PORT;
    private static int updatePort = DEFAULT_UPDATE_PORT;

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
                        "host=" + DEFAULT_HOST,
                        "port=" + DEFAULT_PORT,
                        "updatePort=" + DEFAULT_UPDATE_PORT) + "\n",
                        StandardCharsets.UTF_8);
            }
            host = kv.getOrDefault("host", DEFAULT_HOST);
            port = parseIntSafe(kv.get("port"), DEFAULT_PORT);
            updatePort = parseIntSafe(kv.get("updateport"), DEFAULT_UPDATE_PORT);
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

    public static String address() {
        return host + ":" + port;
    }
}
