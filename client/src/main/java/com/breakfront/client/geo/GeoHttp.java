package com.breakfront.client.geo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Geekhonize Auth HTTP 工具（客户端直连 auth.geekhonize.top）。
 * 端点可经 config 键 auth.endpoint 覆盖。JSON 极简解析，零外部依赖。
 */
public final class GeoHttp {

    /** 端点（config auth.endpoint 可覆盖；默认生产地址）。 */
    public static volatile String endpoint = "https://auth.geekhonize.top";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private GeoHttp() {
    }

    public record Res(boolean ok, String token, String username, String msg) {
    }

    /** 注册并自动登录（app=breakfront）。 */
    public static Res register(String user, String pass, String display) {
        return post("/api/v1/auth/register",
                "{\"username\":\"" + esc(user) + "\",\"password\":\"" + esc(pass)
                        + "\",\"app\":\"breakfront\""
                        + (display == null || display.isEmpty() ? "" : ",\"display_name\":\"" + esc(display) + "\"")
                        + "}");
    }

    /** 登录。 */
    public static Res login(String user, String pass) {
        return post("/api/v1/auth/login",
                "{\"username\":\"" + esc(user) + "\",\"password\":\"" + esc(pass)
                        + "\",\"app\":\"breakfront\"}");
    }

    private static Res post(String path, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint + path))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            String text = resp.body();
            if (resp.statusCode() != 200) {
                return new Res(false, "", "", "服务返回 " + resp.statusCode());
            }
            boolean ok = text.contains("\"ok\":true");
            String token = quoted(text, "access_token");
            String user = quoted(text, "username");
            String msg = ok ? "ok" : quoted(text, "msg");
            return new Res(ok, token, user, msg == null ? "" : msg);
        } catch (Exception e) {
            return new Res(false, "", "", "网络错误：" + e.getClass().getSimpleName());
        }
    }

    private static String quoted(String json, String key) {
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) {
            return null;
        }
        int q1 = json.indexOf('"', k + needle.length());
        if (q1 < 0) {
            return null;
        }
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) {
            return null;
        }
        return json.substring(q1 + 1, q2);
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
