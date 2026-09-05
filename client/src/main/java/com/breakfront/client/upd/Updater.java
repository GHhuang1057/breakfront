package com.breakfront.client.upd;

import net.fabricmc.loader.api.FabricLoader;
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
 * 差异则下载到 bfupdate/ 暂存并返回「需重启」结果。
 *
 * 由于运行中的 jar 在 Windows 上被 JVM 占用、无法直接覆盖，替换动作交由
 * {@link #armAutoApply()} 生成的影子脚本完成：游戏进程退出瞬间自动把暂存
 * 文件拷入 mods/，并尝试用原启动命令行自动重新拉起游戏（全程无需手动操作）。
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

    /** 影子替换进程本会话只武装一次（同 JVM 重复进入重启卡不重复拉起）。 */
    private static volatile boolean watcherArmed = false;

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
            List<String> changed = new ArrayList<>();
            for (RemoteFile rf : remote) {
                String modId = "client".equals(rf.role) ? "breakfront-client" : "breakfront";
                Optional<Path> local = installedJar(modId);
                boolean need = local.isEmpty()
                        || !Files.isRegularFile(local.get())
                        || !rf.sha256.equals(sha256File(local.get()));
                if (!need) {
                    continue;
                }
                if (stage(base, rf, modsDir, stageDir)) {
                    changed.add(rf.name);
                }
            }
            if (changed.isEmpty()) {
                return new Result(Outcome.OK, "已是最新");
            }
            String names = String.join(" / ", changed);
            LOGGER.info("[Breakfront] update staged: {} (auto-apply armed on restart)", names);
            return new Result(Outcome.UPDATED_REQUIRES_RESTART,
                    "已下载新版本模组：" + names + "。应用更新后自动重启游戏，无需手动操作。");
        } catch (Exception e) {
            LOGGER.info("[Breakfront] update source unreachable: {}", e.toString());
            return new Result(Outcome.SKIPPED_NO_SOURCE, "更新源不可达（跳过更新）");
        }
    }

    /**
     * 下载到 bfupdate/ 暂存；若目标未被占用（非 Windows / 非本会话加载的 jar）
     * 则直接就地替换，否则留待影子脚本在退出后复制。
     */
    private static boolean stage(String base, RemoteFile rf, Path modsDir, Path stageDir) {
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
            Files.createDirectories(stageDir);
            Path staged = stageDir.resolve(rf.name);
            Files.write(staged, body);
            try {
                Files.createDirectories(modsDir);
                Files.move(staged, modsDir.resolve(rf.name), StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("[Breakfront] replaced {} directly", rf.name);
            } catch (IOException e) {
                // 被占用 → 保留暂存，影子脚本在进程退出后处理
                LOGGER.info("[Breakfront] {} locked -> staged for auto-apply", rf.name);
            }
            return true;
        } catch (Exception e) {
            LOGGER.warn("[Breakfront] download {} failed: {}", rf.name, e.toString());
            return false;
        }
    }

    /**
     * 武装「影子自动应用」：写 apply-update.bat（等待本进程退出 → 拷贝暂存 jar 到
     * mods → 用原命令行自动拉起游戏），并后台分离启动之。调用方随后应立即退出游戏。
     *
     * @return true 表示武装成功（随后 scheduleStop 即可）；false 表示失败（保留暂存，
     * 可让用户手动运行 bfupdate\apply-update.bat）
     */
    public static synchronized boolean armAutoApply() {
        if (watcherArmed) {
            return true;
        }
        try {
            Path gameDir = FabricLoader.getInstance().getGameDir();
            Path stageDir = gameDir.resolve("bfupdate");
            Files.createDirectories(stageDir);
            Path bat = stageDir.resolve("apply-update.bat");
            long pid = ProcessHandle.current().pid();
            String cmdline = ProcessHandle.current().info().commandLine().orElse("");
            Files.writeString(bat, watcherScript(pid, cmdline));
            new ProcessBuilder("cmd.exe", "/c", "start", "", "/min",
                    bat.toAbsolutePath().toString())
                    .start();
            watcherArmed = true;
            LOGGER.info("[Breakfront] auto-apply watcher armed (pid={})", pid);
            return true;
        } catch (Exception e) {
            LOGGER.warn("[Breakfront] arm auto-apply watcher failed: {}", e.toString());
            return false;
        }
    }

    /** 影子脚本内容。cmdline 原样取自游戏启动命令行；为空则只替换不拉起。 */
    private static String watcherScript(long pid, String cmdline) {
        StringBuilder sb = new StringBuilder();
        sb.append("@echo off\r\n");
        sb.append("rem BREAKFRONT auto-update: wait for game exit, swap jars, relaunch\r\n");
        sb.append("set \"GAMEPID=").append(pid).append("\"\r\n");
        sb.append(":wait\r\n");
        sb.append("tasklist /FI \"PID eq %GAMEPID%\" 2>nul | find \"%GAMEPID%\" >nul\r\n");
        sb.append("if not errorlevel 1 (\r\n");
        sb.append("  timeout /t 1 /nobreak >nul\r\n");
        sb.append("  goto :wait\r\n");
        sb.append(")\r\n");
        sb.append("rem game closed -> swap staged jars into mods\r\n");
        sb.append("copy /Y \"%~dp0*.jar\" \"%~dp0..\\mods\\\" >nul\r\n");
        sb.append("del /Q \"%~dp0breakfront*.jar\" 2>nul\r\n");
        if (cmdline != null && !cmdline.isBlank()) {
            sb.append("rem relaunch game with the original launch command (best-effort)\r\n");
            sb.append("start \"\" ").append(cmdline).append("\r\n");
        }
        sb.append("exit\r\n");
        return sb.toString();
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
