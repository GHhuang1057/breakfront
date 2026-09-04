package com.breakfront.client.upd;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 客户端模组自更新（从对局服务端拉取配套 jar）。
 *
 * 流程：PLAY 前 GET http://host:updatePort/breakfront/manifest.json，
 * 对比本机已装 breakfront / breakfront-client 的 sha256；
 * 差异则下载替换到 mods/ 同名文件，返回「需重启」结果由界面提示。
 * 更新源不可达时不阻塞联机（跳过并照常连接）。
 */
public final class Updater {

    private static final Logger LOGGER = LoggerFactory.getLogger("breakfront.updater");

    private Updater() {
    }

    public enum Outcome {
        /** 已是最新，可直接连接 */
        OK,
        /** 已下载新 jar，需重启游戏后生效 */
        UPDATED_REQUIRES_RESTART,
        /** 更新源不可达（跳过更新，仍可连接） */
        SKIPPED_NO_SOURCE,
        /** 解析/下载异常（不阻塞连接，但记录提示） */
        ERROR
    }

    public record Result(Outcome outcome, String message) {
        public boolean proceedToConnect() {
            return outcome == Outcome.OK || outcome == Outcome.SKIPPED_NO_SOURCE || outcome == Outcome.ERROR;
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public static Result run(String host, int updatePort) {
        try {
            String base = "http://" + host + ":" + updatePort;
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/breakfront/manifest.json"))
                    .timeout(Duration.ofSeconds(6))
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return new Result(Outcome.SKIPPED_NO_SOURCE, "更新源返回 " + resp.statusCode());
            }
            List<RemoteFile> remote = parseManifest(resp.body());
            if (remote.isEmpty()) {
                return new Result(Outcome.SKIPPED_NO_SOURCE, "更新源暂未配置模组文件");
            }
            Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
            boolean anyUpdate = false;
            int updated = 0;
            List<String> notes = new ArrayList<>();
            for (RemoteFile rf : remote) {
                String modId = "client".equals(rf.role) ? "breakfront-client" : "breakfront";
                Optional<Path> local = installedJar(modId);
                boolean need = !local.isPresent()
                        || !Files.isRegularFile(local.get())
                        || !rf.sha256.equals(sha256File(local.get()));
                if (!need) {
                    continue;
                }
                if (download(base, rf, modsDir)) {
                    updated++;
                    anyUpdate = true;
                } else {
                    notes.add(rf.name + " 写入失败，请手动替换到 mods/");
                }
            }
            if (anyUpdate) {
                StringBuilder msg = new StringBuilder("检测到新版本模组，已自动更新 ").append(updated).append(" 个文件。");
                if (!notes.isEmpty()) {
                    msg.append(String.join("；", notes));
                }
                msg.append("请完全退出并重启游戏后重新进入。");
                return new Result(Outcome.UPDATED_REQUIRES_RESTART, msg.toString());
            }
            return new Result(Outcome.OK, "已是最新");
        } catch (Exception e) {
            LOGGER.info("[Breakfront] update source unreachable: {}", e.toString());
            return new Result(Outcome.SKIPPED_NO_SOURCE, "更新源不可达（跳过更新）");
        }
    }

    private static boolean download(String base, RemoteFile rf, Path modsDir) {
        Path target = modsDir.resolve(rf.name);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/breakfront/files/" + rf.name))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                return false;
            }
            byte[] body = resp.body();
            if (!rf.sha256.equals(sha256(body))) {
                LOGGER.warn("[Breakfront] {} checksum mismatch after download", rf.name);
                return false;
            }
            Files.createDirectories(modsDir);
            Path tmp = modsDir.resolve(rf.name + ".partial");
            Files.write(tmp, body);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // Windows 上加载中的 jar 可能被占用：尽力替换，失败则保留 .partial 待下次
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            LOGGER.info("[Breakfront] updated {} -> {}", rf.name, rf.sha256.substring(0, 12));
            return true;
        } catch (Exception e) {
            LOGGER.warn("[Breakfront] download {} failed: {}", rf.name, e.toString());
            return false;
        }
    }

    private static Optional<Path> installedJar(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .flatMap(c -> c.getOrigin().getPaths().stream().findFirst());
    }

    private static String sha256File(Path p) {
        try {
            return sha256(Files.readAllBytes(p));
        } catch (IOException e) {
            return "";
        }
    }

    private static String sha256(byte[] data) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 极简 manifest 解析（固定结构，避免引 JSON 库）。 */
    private static List<RemoteFile> parseManifest(String body) {
        List<RemoteFile> out = new ArrayList<>();
        int i = 0;
        while (i < body.length()) {
            int bs = body.indexOf('{', i);
            int be = body.indexOf('}', bs);
            if (bs < 0 || be < 0) {
                break;
            }
            String obj = body.substring(bs + 1, be);
            i = be + 1;
            String name = quoted(obj, "name");
            String role = quoted(obj, "role");
            String sha = quoted(obj, "sha256");
            if (name != null && role != null && sha != null) {
                out.add(new RemoteFile(name, role, sha));
            }
        }
        return out;
    }

    private static String quoted(String obj, String key) {
        int k = obj.indexOf('"' + key + '"');
        if (k < 0) {
            return null;
        }
        int c = obj.indexOf(':', k);
        if (c < 0) {
            return null;
        }
        int q1 = obj.indexOf('"', c);
        if (q1 < 0) {
            return null;
        }
        int q2 = obj.indexOf('"', q1 + 1);
        if (q2 < 0) {
            return null;
        }
        return obj.substring(q1 + 1, q2);
    }

    private record RemoteFile(String name, String role, String sha256) {
    }
}
