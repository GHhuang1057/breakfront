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

    public record Res(boolean ok, String token, String username, String msg, String devCode) {
        public Res(boolean ok, String token, String username, String msg) {
            this(ok, token, username, msg, "");
        }
    }

    /** 发送邮箱验证码（注册/找回/换绑）。 */
    public static Res sendCode(String email, String purpose) {
        return post("/api/v1/auth/send_code",
                "{\"email\":\"" + esc(email.trim()) + "\",\"purpose\":\"" + esc(purpose) + "\"}");
    }

    /** 注册（需邮箱验证码）并自动登录（app=breakfront）。 */
    public static Res register(String user, String pass, String email, String code) {
        return post("/api/v1/auth/register",
                "{\"username\":\"" + esc(user) + "\",\"password\":\"" + esc(pass)
                        + "\",\"email\":\"" + esc(email.trim()) + "\",\"code\":\"" + esc(code.trim())
                        + "\",\"app\":\"breakfront\"}");
    }

    /** 登录。 */
    public static Res login(String user, String pass) {
        return post("/api/v1/auth/login",
                "{\"username\":\"" + esc(user) + "\",\"password\":\"" + esc(pass)
                        + "\",\"app\":\"breakfront\"}");
    }

    /**
     * 校验一个已有令牌（{@code GET /api/v1/auth/me}）——「令牌登录」用。
     *
     * <p>玩家在账号中心（auth.geekhonize.top → 我的账号 → 游戏令牌）生成长期令牌，
     * 复制到游戏内粘贴即可完成绑定，无需输账号密码、也不用邮箱验证码往返。
     * 服务端 {@code AuthBridge.me} 校验的是同一个端点，故两边口径一致。
     */
    public static Res me(String token) {
        String tk = token == null ? "" : token.trim();
        if (tk.isEmpty()) {
            return new Res(false, "", "", "令牌为空");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint + "/api/v1/auth/me"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + tk)
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                String msg = quoted(resp.body(), "msg");
                return new Res(false, "", "",
                        msg == null ? "令牌校验失败（HTTP " + resp.statusCode() + "）" : msg);
            }
            String user = quoted(resp.body(), "username");
            if (user == null || user.isEmpty()) {
                return new Res(false, "", "", "令牌响应缺少用户名");
            }
            return new Res(true, tk, user, "ok");
        } catch (Exception e) {
            return new Res(false, "", "", "网络错误：" + e.getClass().getSimpleName());
        }
    }

    // ---------- 设备码登录（PCL/FCL 等第三方启动器：游戏内跳浏览器授权） ----------

    public record DeviceStart(boolean ok, String code, String msg) {
    }

    /** 请求 6 位设备码（10 分钟有效）。 */
    public static DeviceStart deviceStart() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint + "/api/v1/auth/device/start"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            String text = resp.body();
            boolean ok = text.contains("\"ok\":true");
            String code = quoted(text, "code");
            if (!ok || code == null || code.isEmpty()) {
                String msg = quoted(text, "msg");
                return new DeviceStart(false, "", msg == null ? "请求设备码失败" : msg);
            }
            return new DeviceStart(true, code, "ok");
        } catch (Exception e) {
            return new DeviceStart(false, "", "网络错误：" + e.getClass().getSimpleName());
        }
    }

    public record DevicePoll(boolean ok, String status, String token, String username, String msg) {
    }

    /** 轮询设备码状态；approved 时携带 token/username。 */
    public static DevicePoll devicePoll(String code) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint + "/api/v1/auth/device/poll"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"code\":\"" + esc(code) + "\"}"))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            String text = resp.body();
            if (!text.contains("\"ok\":true")) {
                String msg = quoted(text, "msg");
                return new DevicePoll(false, "", "", "", msg == null ? "轮询失败" : msg);
            }
            String status = quoted(text, "status");
            if (!"approved".equals(status)) {
                return new DevicePoll(true, "pending", "", "", "ok");
            }
            String token = quoted(text, "access_token");
            String user = quoted(text, "username");
            if (token == null || token.isEmpty()) {
                return new DevicePoll(false, "", "", "", "授权响应缺少令牌");
            }
            return new DevicePoll(true, "approved", token, user == null ? "" : user, "ok");
        } catch (Exception e) {
            return new DevicePoll(false, "", "", "", "网络错误：" + e.getClass().getSimpleName());
        }
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
                return new Res(false, "", "", "服务返回 " + resp.statusCode(), "");
            }
            boolean ok = text.contains("\"ok\":true");
            String token = quoted(text, "access_token");
            String user = quoted(text, "username");
            String msg = ok ? "ok" : quoted(text, "msg");
            String devCode = quoted(text, "dev_code");
            return new Res(ok, token, user, msg == null ? "" : msg,
                    devCode == null ? "" : devCode);
        } catch (Exception e) {
            return new Res(false, "", "", "网络错误：" + e.getClass().getSimpleName(), "");
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
