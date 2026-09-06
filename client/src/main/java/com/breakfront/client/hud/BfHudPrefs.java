package com.breakfront.client.hud;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 客户端 HUD 偏好（静态、只读 config）：
 * 读取 {gameDir}/config/breakfront-client.properties，键：
 *   hud.bob   = true|false   视野视差（默认 true）
 *   hud.shake = true|false|0..2  落地/走路抖动强度（默认 true=1.0；false/0=关闭）
 * 与 BfServerConfig 共用同一 properties 文件；带 ~5s 缓存刷新（渲染线程内读取，单线程安全）。
 */
public final class BfHudPrefs {

    private static final String FILE_NAME = "breakfront-client.properties";
    private static final long CACHE_MS = 5000L;

    private static volatile boolean bobEnabled = true;
    private static volatile boolean shakeEnabled = true;
    private static volatile double shakeStrength = 1.0;
    private static volatile long loadedAt = 0;

    private BfHudPrefs() {
    }

    private static void reloadIfStale() {
        long now = System.currentTimeMillis();
        if (now - loadedAt < CACHE_MS) {
            return;
        }
        loadedAt = now;
        Map<String, String> kv = new HashMap<>();
        try {
            Path file = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
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
            }
        } catch (IOException | RuntimeException e) {
            // 读不到：保留默认
        }
        bobEnabled = parseBool(kv.get("hud.bob"), true);
        String sh = kv.get("hud.shake");
        if (sh == null) {
            shakeEnabled = true;
            shakeStrength = 1.0;
        } else if (isFalse(sh)) {
            shakeEnabled = false;
            shakeStrength = 0.0;
        } else {
            shakeEnabled = true;
            shakeStrength = clamp(parseDouble(sh, 1.0), 0.0, 2.0);
        }
    }

    /** 视野视差（view bob）是否启用。默认 true。 */
    public static boolean isBobEnabled() {
        reloadIfStale();
        return bobEnabled;
    }

    /** 落地/走路抖动是否启用（hud.shake=false|0 时关闭）。 */
    public static boolean isShakeEnabled() {
        reloadIfStale();
        return shakeEnabled;
    }

    /** 抖动强度系数（0..2，默认 1.0）。 */
    public static double getShake() {
        reloadIfStale();
        return shakeStrength;
    }

    private static boolean parseBool(String v, boolean def) {
        if (v == null) {
            return def;
        }
        return !isFalse(v);
    }

    private static boolean isFalse(String v) {
        return v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off");
    }

    private static double parseDouble(String v, double def) {
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
