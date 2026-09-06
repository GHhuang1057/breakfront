package com.breakfront.client.geo;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 本地 Geekhonize 会话（token/用户名）——存于 config/breakfront-client.properties。
 * 离线服客户端凭 token 进服，进服后自动发送 AuthLoginPayload 与服务器绑定。
 */
public final class GeoSession {

    private static volatile String token = "";
    private static volatile String username = "";
    private static final String KEY_TOKEN = "auth.token";
    private static final String KEY_USER = "auth.username";

    private GeoSession() {
    }

    public static void load() {
        Path p = props();
        Map<String, String> kv = new HashMap<>();
        try {
            for (String line : Files.readAllLines(p)) {
                int e = line.indexOf('=');
                if (e > 0) {
                    kv.put(line.substring(0, e).trim(), line.substring(e + 1).trim());
                }
            }
        } catch (Exception ignored) {
        }
        token = kv.getOrDefault(KEY_TOKEN, "");
        username = kv.getOrDefault(KEY_USER, "");
    }

    public static String token() {
        return token;
    }

    public static String username() {
        return username;
    }

    public static boolean signedIn() {
        return !token.isEmpty();
    }

    public static void save(String newToken, String newUser) {
        token = newToken == null ? "" : newToken.trim();
        username = newUser == null ? "" : newUser.trim();
        try {
            Path p = props();
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            java.util.List<String> lines = new java.util.ArrayList<>();
            if (Files.isRegularFile(p)) {
                lines.addAll(Files.readAllLines(p));
            }
            // 行级替换 auth.*（保留用户其它配置键，如 host/hud.bob）
            lines.removeIf(l -> l.startsWith(KEY_TOKEN + "=") || l.startsWith(KEY_USER + "="));
            lines.add(KEY_TOKEN + "=" + token);
            lines.add(KEY_USER + "=" + username);
            Files.writeString(p, String.join("\n", lines) + "\n");
        } catch (Exception ignored) {
        }
    }

    public static void clear() {
        save("", "");
    }

    private static Path props() {
        return FabricLoader.getInstance().getConfigDir().resolve("breakfront-client.properties");
    }
}
