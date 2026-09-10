package com.breakfront.client.geo;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 本地 Geekhonize 会话（token / 用户名 / 过期时间）—— 存于 config/breakfront-client.properties。
 * 离线服客户端凭 token 进服，进服后自动发送 AuthLoginPayload 与服务器绑定。
 *
 * <p>令牌是 JWT（HS256，含 iat/exp），客户端无需验签即可读取 {@code exp} 判断有效期，
 * 并在临近过期时自动调用 {@link GeoHttp#refresh(String)} 续期，避免「明明登录了却突然绑不上」。</p>
 */
public final class GeoSession {

    private static volatile String token = "";
    private static volatile String username = "";
    /** 令牌过期时间（epoch 秒，0=未知/无法解析）。 */
    private static volatile long expiresAt = 0;

    private static final String KEY_TOKEN = "auth.token";
    private static final String KEY_USER = "auth.username";
    private static final String KEY_EXPIRES = "auth.expires";

    /** 距过期不足该秒数即视为需要续期。 */
    private static final long REFRESH_THRESHOLD_SEC = 10 * 60;

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
        try {
            expiresAt = Long.parseLong(kv.getOrDefault(KEY_EXPIRES, "0"));
        } catch (Exception ignored) {
            expiresAt = 0;
        }
    }

    public static String token() {
        return token;
    }

    public static String username() {
        return username;
    }

    /** 令牌过期时间（epoch 秒）。 */
    public static long expiresAt() {
        return expiresAt;
    }

    /** 是否已登录（仅看是否有令牌；是否生效以 {@link #isExpired()} 为准）。 */
    public static boolean signedIn() {
        return !token.isEmpty();
    }

    /** 令牌是否已过期。 */
    public static boolean isExpired() {
        return expiresAt > 0 && System.currentTimeMillis() / 1000 >= expiresAt;
    }

    /** 是否临近过期（需要续期）。 */
    public static boolean isExpiringSoon() {
        if (expiresAt == 0) {
            return false; // 无法判断时保守不主动续期
        }
        return System.currentTimeMillis() / 1000 >= (expiresAt - REFRESH_THRESHOLD_SEC);
    }

    /**
     * 落盘会话。会从 token 中解析 exp 一并保存，调用方无需关心过期时间。
     */
    public static void save(String newToken, String newUser) {
        token = newToken == null ? "" : newToken.trim();
        username = newUser == null ? "" : newUser.trim();
        expiresAt = decodeExpiry(token);
        persist();
    }

    /** 仅更新过期时间（续期成功后调用，避免覆盖用户名）。 */
    public static void touchExpiry(long exp) {
        expiresAt = exp;
        persist();
    }

    public static void clear() {
        save("", "");
    }

    private static void persist() {
        try {
            Path p = props();
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            List<String> lines = new java.util.ArrayList<>();
            if (Files.isRegularFile(p)) {
                lines.addAll(Files.readAllLines(p));
            }
            // 行级替换 auth.*（保留用户其它配置键，如 host/hud.bob）
            lines.removeIf(l -> l.startsWith(KEY_TOKEN + "=")
                    || l.startsWith(KEY_USER + "=")
                    || l.startsWith(KEY_EXPIRES + "="));
            lines.add(KEY_TOKEN + "=" + token);
            lines.add(KEY_USER + "=" + username);
            lines.add(KEY_EXPIRES + "=" + expiresAt);
            Files.writeString(p, String.join("\n", lines) + "\n");
        } catch (Exception ignored) {
        }
    }

    /**
     * 临近过期或已过期时自动续期。结果通过回调返回（true=当前会话有效，false=需要重新登录）。
     * 网络在异步线程执行，回调在主线程执行。
     */
    public static void refreshIfNeeded(java.util.function.Consumer<Boolean> cb) {
        if (token.isEmpty()) {
            if (cb != null) {
                cb.accept(false);
            }
            return;
        }
        if (!isExpiringSoon()) {
            if (cb != null) {
                cb.accept(true);
            }
            return;
        }
        final String old = token;
        CompletableFuture.supplyAsync(() -> GeoHttp.refresh(old))
                .thenAccept(r -> {
                    if (r.ok() && !r.token().isEmpty()) {
                        save(r.token(), r.username().isEmpty() ? username : r.username());
                        if (cb != null) {
                            cb.accept(true);
                        }
                    } else {
                        // 续期失败：若确实已过期则清会话要求重登，否则保留旧令牌
                        if (isExpired()) {
                            clear();
                            if (cb != null) {
                                cb.accept(false);
                            }
                        } else if (cb != null) {
                            cb.accept(true);
                        }
                    }
                })
                .exceptionally(e -> {
                    if (cb != null) {
                        cb.accept(!isExpired());
                    }
                    return null;
                });
    }

    /** 读取 JWT payload 里的 exp（epoch 秒），失败返回 0。 */
    private static long decodeExpiry(String jwt) {
        if (jwt == null || jwt.isEmpty()) {
            return 0;
        }
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                return 0;
            }
            String b64 = parts[1].replace('-', '+').replace('_', '/');
            while (b64.length() % 4 != 0) {
                b64 += "=";
            }
            byte[] decoded = Base64.getDecoder().decode(b64);
            String json = new String(decoded, StandardCharsets.UTF_8);
            int i = json.indexOf("\"exp\"");
            if (i < 0) {
                return 0;
            }
            int c = json.indexOf(':', i);
            int s = c + 1;
            while (s < json.length() && (json.charAt(s) == ' ' || json.charAt(s) == '"')) {
                s++;
            }
            int e = s;
            while (e < json.length() && Character.isDigit(json.charAt(e))) {
                e++;
            }
            return Long.parseLong(json.substring(s, e));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static Path props() {
        return FabricLoader.getInstance().getConfigDir().resolve("breakfront-client.properties");
    }
}
