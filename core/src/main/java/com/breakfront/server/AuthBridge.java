package com.breakfront.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Geekhonize Auth 桥（core 侧 HTTP 客户端，服务端运行）。
 *
 * 职责：玩家提交 GEO 令牌 → 调 Auth /api/v1/auth/me 校验并取角色；
 * 管理台代理登录 → /api/v1/auth/login（app=geekhonize-portal）。
 * 端点由 props 键 auth.endpoint 配置（默认 https://auth.geekhonize.top）。
 */
public final class AuthBridge {

    public static volatile String endpoint = "https://auth.geekhonize.top";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private AuthBridge() {
    }

    /** me 校验结果。 */
    public record Me(String username, long uid, List<String> roles, boolean ok, String msg) {
        public boolean isAdmin() {
            return roles.contains("admin");
        }
    }

    /** 校验令牌（同步；调用方请放到 IO 线程）。 */
    public static Me me(String token) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint + "/api/v1/auth/me"))
                    .timeout(Duration.ofSeconds(6))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return new Me("", 0, List.of(), false, "Auth 返回 " + resp.statusCode());
            }
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            JsonObject data = root.getAsJsonObject("data");
            List<String> roles = new ArrayList<>();
            data.getAsJsonArray("roles").forEach(e -> roles.add(e.getAsString()));
            return new Me(data.get("username").getAsString(), data.get("uid").getAsLong(),
                    roles, true, "ok");
        } catch (Exception e) {
            return new Me("", 0, List.of(), false, "Auth 不可达: " + e.getMessage());
        }
    }

    /** 账号密码登录（管理台代理用；返回 access token 或错误）。 */
    public record LoginResult(boolean ok, String token, String username, List<String> roles, String msg) {
    }

    public static LoginResult login(String username, String password) {
        try {
            String body = "{\"username\":\"" + esc(username) + "\",\"password\":\""
                    + esc(password) + "\",\"app\":\"geekhonize-portal\"}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint + "/api/v1/auth/login"))
                    .timeout(Duration.ofSeconds(6))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return new LoginResult(false, "", "", List.of(), "Auth 登录失败 " + resp.statusCode());
            }
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (!root.get("ok").getAsBoolean()) {
                return new LoginResult(false, "", "", List.of(), "账号或密码错误");
            }
            JsonObject user = root.getAsJsonObject("user");
            List<String> roles = new ArrayList<>();
            user.getAsJsonArray("roles").forEach(e -> roles.add(e.getAsString()));
            return new LoginResult(true, root.get("access_token").getAsString(),
                    user.get("username").getAsString(), roles, "ok");
        } catch (Exception e) {
            return new LoginResult(false, "", "", List.of(), "Auth 不可达");
        }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
