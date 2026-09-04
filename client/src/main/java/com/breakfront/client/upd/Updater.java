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

    /** 轻量探测更新源是否在线（主菜单状态徽章用）。 */
    public static boolean probe(String host, int updatePort) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://" + host + ":" + updatePort
                            + "/breakfront/manifest.json"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            return HTTP.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

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
            Path gameDir = FabricLoader.getInstance().getGameDir();
            Path modsDir = gameDir.resolve("mods");
            Path stageDir = gameDir.resolve("bfupdate");
            int applied = 0;
            int staged = 0;
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
                DownloadOutcome d = download(base, rf, modsDir, stageDir);
                switch (d) {
                    case APPLIED -> {
                        applied++;
                        notes.add(rf.name + " 已自动替换（重启生效）");
                    }
                    case STAGED -> {
                        staged++;
                        notes.add(rf.name + " 已下载，关闭游戏后双击 bfupdate\\apply-update.bat 应用");
                    }
                    case FAIL -> notes.add(rf.name + " 下载失败，请手动更新");
                }
            }
            if (applied == 0 && staged == 0 && notes.isEmpty()) {
                return new Result(Outcome.OK, "已是最新");
            }
            if (applied == 0 && staged == 0) {
                return new Result(Outcome.ERROR, "更新失败：" + String.join("；", notes));
            }
            StringBuilder msg = new StringBuilder("发现新版本模组（").append(applied).append(" 已应用 / ")
                    .append(staged).append(" 待应用）。");
            if (!notes.isEmpty()) {
                msg.append(String.join("；", notes)).append("。");
            }
            msg.append("请完全退出并重启游戏后重新进入。");
            return new Result(Outcome.UPDATED_REQUIRES_RESTART, msg.toString());
        } catch (Exception e) {
            LOGGER.info("[Breakfront] update source unreachable: {}", e.toString());
            return new Result(Outcome.SKIPPED_NO_SOURCE, "更新源不可达（跳过更新）");
        }
    }

    private enum DownloadOutcome { APPLIED, STAGED, FAIL }

    /**
     * 下载到 bfupdate/ 暂存（同时生成一键应用脚本），随后尝试直接替换 mods/ 同名文件；
     * 运行中的 jar 在 Windows 上通常被占用 → 失败则保留暂存并交由脚本/手动应用。
     */
    private static DownloadOutcome download(String base, RemoteFile rf, Path modsDir, Path stageDir) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/breakfront/files/" + rf.name))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                return DownloadOutcome.FAIL;
            }
            byte[] body = resp.body();
            if (!rf.sha256.equals(sha256(body))) {
                LOGGER.warn("[Breakfront] {} checksum mismatch after download", rf.name);
                return DownloadOutcome.FAIL;
            }
            Files.createDirectories(stageDir);
            Path staged = stageDir.resolve(rf.name);
            Files.write(staged, body);
            writeApplyScript(stageDir);
            try {
                Path target = modsDir.resolve(rf.name);
                Files.createDirectories(modsDir);
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("[Breakfront] updated {} -> {}", rf.name, rf.sha256.substring(0, 12));
                return DownloadOutcome.APPLIED;
            } catch (IOException e) {
                LOGGER.info("[Breakfront] {} is locked, staged for apply-update script", rf.name);
                return DownloadOutcome.STAGED;
            }
        } catch (Exception e) {
            LOGGER.warn("[Breakfront] download {} failed: {}", rf.name, e.toString());
            return DownloadOutcome.FAIL;
        }
    }

    /** 生成「关闭游戏后一键应用更新」脚本（英文输出避免编码问题）。 */
    private static void writeApplyScript(Path stageDir) {
        try {
            Path bat = stageDir.resolve("apply-update.bat");
            String content = "@echo off\r\n"
                    + "echo BREAKFRONT: applying mod update...\r\n"
                    + "copy /Y \"%~dp0*.jar\" \"%~dp0..\\mods\\\"\r\n"
                    + "echo Done. You can start the game now.\r\n"
                    + "pause\r\n";
            if (!Files.isRegularFile(bat)) {
                Files.writeString(bat, content);
            }
        } catch (IOException e) {
            LOGGER.warn("[Breakfront] write apply-update.bat failed: {}", e.toString());
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
