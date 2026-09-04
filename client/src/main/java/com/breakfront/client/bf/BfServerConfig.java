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
 * 现阶段默认直连本地开发服；正式上线域名将由运营方在此单点替换
 * （或编辑 config/breakfront-client.properties 的 host/port）。
 * 客户端因此不需要再走原版「服务器选择」。
 */
public final class BfServerConfig {

    /** 上线域名位：换这里即可全网切换。 */
    public static final String DEFAULT_HOST = "localhost";
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
                        "# host 上线后替换为正式服务器域名/地址",
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
