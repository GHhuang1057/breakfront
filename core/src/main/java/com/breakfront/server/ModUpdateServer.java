package com.breakfront.server;

import com.breakfront.Breakfront;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BREAKFRONT 模组更新源（轻量 HTTP，随对局服务端启动）。
 *
 * 职责：向客户端提供「服务端配套的 breakfront / breakfront-client」版本清单与 jar 下载，
 * 支撑客户端一键自更新（连服前比对 sha256 → 下载替换 → 提示重启）。
 *
 * 文件来源（依次回退）：
 *   1) 服务器运行目录 breakfront-sync/<name>（运营方放正式发布 jar）
 *   2) 服务器自身 mods/<name>（开发环境直接用 dev 包，即装即用）
 * 典型端口 25610（可用系统属性 -Dbreakfront.update.port 覆盖）。
 */
public final class ModUpdateServer {

    private static final String[] CANONICAL_FILES = {
            "breakfront-0.1.0.jar",
            "breakfront-client-0.1.0.jar"
    };

    private HttpServer server;
    private final Map<String, Path> sources = new LinkedHashMap<>();
    private final String manifestJson;

    private ModUpdateServer(Path runDir) throws IOException {
        Path sync = runDir.resolve("breakfront-sync");
        Path mods = runDir.resolve("mods");
        for (String name : CANONICAL_FILES) {
            Path p = sync.resolve(name);
            if (!Files.isRegularFile(p)) {
                p = mods.resolve(name);
            }
            if (Files.isRegularFile(p)) {
                sources.put(name, p);
            }
        }
        manifestJson = buildManifest(sources);
    }

    public static ModUpdateServer start(Path runDir) {
        try {
            ModUpdateServer mu = new ModUpdateServer(runDir);
            int port = Integer.getInteger("breakfront.update.port", 25610);
            mu.server = HttpServer.create(new InetSocketAddress(port), 0);
            mu.server.createContext("/breakfront/manifest.json", exchange -> {
                byte[] body = mu.manifestJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            mu.server.createContext("/breakfront/files/", exchange -> {
                String name = exchange.getRequestURI().getPath()
                        .substring("/breakfront/files/".length());
                Path file = mu.sources.get(name);
                if (file == null || !Files.isRegularFile(file)) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                byte[] data = Files.readAllBytes(file);
                exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
                exchange.sendResponseHeaders(200, data.length);
                exchange.getResponseBody().write(data);
                exchange.close();
            });
            mu.server.setExecutor(null);
            mu.server.start();
            int count = mu.sources.size();
            Breakfront.LOGGER.info("[Breakfront] mod update source on :{} ({} file(s) available)",
                    port, count);
            return mu;
        } catch (IOException e) {
            Breakfront.LOGGER.warn("[Breakfront] mod update source failed to start: {}", e.toString());
            return null;
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private static String buildManifest(Map<String, Path> sources) throws IOException {
        StringBuilder sb = new StringBuilder("{\"files\":[");
        boolean first = true;
        for (Map.Entry<String, Path> e : sources.entrySet()) {
            Path f = e.getValue();
            byte[] data = Files.readAllBytes(f);
            String role = e.getKey().startsWith("breakfront-client") ? "client" : "core";
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"name\":\"").append(e.getKey())
                    .append("\",\"role\":\"").append(role)
                    .append("\",\"sha256\":\"").append(sha256(data))
                    .append("\",\"size\":").append(data.length).append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    public static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(data);
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
