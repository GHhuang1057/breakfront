package com.breakfront.client.upd;

import com.breakfront.client.bf.BfServerConfig;
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
            .connectTimeout(Duration.ofSeconds(10))   // 国内→CF 边缘 TCP/TLS 冷握手可能超 3s
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** 影子替换进程本会话只武装一次（同 JVM 重复进入重启卡不重复拉起）。 */
    private static volatile boolean watcherArmed = false;

    /** 轻量探测更新源是否在线（主菜单状态徽章用）。
     * ⚠️ 国内直连 Cloudflare 边缘：冷启动 DNS+TLS 握手抖动很容易破 3s（实测常态 0.7~1.8s），
     * 单次 3s 探测会间歇性误报「更新源离线」→ 放宽到 8s×2 次。 */
    public static boolean probe(String host, int updatePort) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(BfServerConfig.updateBase()
                                + "/breakfront/manifest.json"))
                        .timeout(Duration.ofSeconds(8))
                        .GET()
                        .build();
                if (HTTP.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return true;
                }
            } catch (Exception e) {
                // 超时/网络抖动：重试一次
            }
        }
        return false;
    }

    /** 进度回调（预检屏使用）：stage 描述当前阶段，frac ∈ [0,1] 0=未知。 */
    public interface Progress {
        void report(String stage, float frac);
    }

    public static Result run(String host, int updatePort) {
        return runWithProgress(host, updatePort, null);
    }

    /** 带进度回调的完整检查+下载（Bootstrap 预检屏调用）。 */
    public static Result runWithProgress(String host, int updatePort, Progress progress) {
        if (progress != null) {
            progress.report("正在检查更新源…", 0.02f);
        }
        try {
            String base = BfServerConfig.updateBase();
            String manifest = fetchWithRetry(base + "/breakfront/manifest.json");
            if (manifest == null) {
                return new Result(Outcome.SKIPPED_NO_SOURCE, "更新源不可达（跳过更新）");
            }
            List<RemoteFile> remote = parseManifest(manifest);
            if (remote.isEmpty()) {
                return new Result(Outcome.SKIPPED_NO_SOURCE, "更新源暂未配置模组文件");
            }
            Path gameDir = FabricLoader.getInstance().getGameDir();
            Path modsDir = gameDir.resolve("mods");
            Path stageDir = gameDir.resolve("bfupdate");

            // 需要更新的文件（本地缺失或 sha 不一致）
            List<RemoteFile> need = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (RemoteFile rf : remote) {
                String modId = "client".equals(rf.role) ? "breakfront-client" : "breakfront";
                Optional<Path> local = installedJar(modId);
                boolean outdated = local.isEmpty()
                        || !Files.isRegularFile(local.get())
                        || !rf.sha256.equals(sha256File(local.get()));
                if (outdated) {
                    need.add(rf);
                    names.add(rf.name);
                }
            }
            if (need.isEmpty()) {
                if (progress != null) {
                    progress.report("模组已是最新", 1.0f);
                }
                return new Result(Outcome.OK, "已是最新");
            }

            if (progress != null) {
                progress.report("正在下载更新模组…", 0.30f);
            }
            // 并行下载到 bfupdate/ 暂存（单文件超时 30s；并行后总时长≈最慢一个文件）
            List<java.util.concurrent.CompletableFuture<Boolean>> jobs = new ArrayList<>();
            for (RemoteFile rf : need) {
                jobs.add(java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> downloadToStage(base, rf, stageDir)));
            }
            int ok = 0;
            int done = 0;
            for (var j : jobs) {
                try {
                    if (Boolean.TRUE.equals(j.get(40, java.util.concurrent.TimeUnit.SECONDS))) {
                        ok++;
                    }
                } catch (Exception je) {
                    LOGGER.warn("[Breakfront] parallel download task failed: {}", je.toString());
                }
                done++;
                if (progress != null) {
                    progress.report("正在下载更新模组…",
                            0.30f + 0.60f * done / Math.max(1, jobs.size()));
                }
            }

            if (progress != null) {
                progress.report(ok == need.size() ? "更新文件校验完成" : "部分下载失败（本次先联机）",
                        ok == need.size() ? 0.95f : 0.7f);
            }
            if (ok == need.size()) {
                // 全部就绪：尝试即时替换（进程内 jar 被占用时自动留给影子脚本）
                for (RemoteFile rf : need) {
                    tryReplace(stageDir, modsDir, rf.name);
                }
                String joined = String.join(" / ", names);
                LOGGER.info("[Breakfront] update staged: {} (auto-apply armed on restart)", joined);
                return new Result(Outcome.UPDATED_REQUIRES_RESTART,
                        "已下载新版本模组：" + joined + "。应用更新后自动重启游戏，无需手动操作。");
            }
            String msg = "模组更新下载未完成（成功 " + ok + "/" + need.size()
                    + "）：本次先联机，稍后重新进入会自动重试更新。";
            LOGGER.warn("[Breakfront] {}", msg);
            return new Result(Outcome.ERROR, msg);
        } catch (Exception e) {
            LOGGER.info("[Breakfront] update source unreachable: {}", e.toString());
            return new Result(Outcome.SKIPPED_NO_SOURCE, "更新源不可达（跳过更新）");
        }
    }

    /** GET 并等待响应体（text）；单次 12s 超时，失败重试最多 3 次（退避 1s/2s）。 */
    private static String fetchWithRetry(String url) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(12))
                        .GET()
                        .build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return resp.body();
                }
                return null; // 明确非 200 不再重试
            } catch (Exception e) {
                if (attempt < 2) {
                    try {
                        Thread.sleep(1000L * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
        }
        return null;
    }

    /** 下载并校验 sha，写入 bfupdate/ 暂存。成功返回 true。 */
    private static boolean downloadToStage(String base, RemoteFile rf, Path stageDir) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/breakfront/files/" + rf.name))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                LOGGER.warn("[Breakfront] {} download http {}", rf.name, resp.statusCode());
                return false;
            }
            byte[] body = resp.body();
            if (!rf.sha256.equals(sha256(body))) {
                LOGGER.warn("[Breakfront] {} checksum mismatch after download", rf.name);
                return false;
            }
            Files.createDirectories(stageDir);
            Files.write(stageDir.resolve(rf.name), body);
            return true;
        } catch (Exception e) {
            LOGGER.warn("[Breakfront] download {} failed: {}", rf.name, e.toString());
            return false;
        }
    }

    /** 暂存文件已全部就绪后的即时替换（失败即留给影子脚本在退出后复制）。 */
    private static void tryReplace(Path stageDir, Path modsDir, String name) {
        try {
            Files.createDirectories(modsDir);
            Files.move(stageDir.resolve(name), modsDir.resolve(name),
                    StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("[Breakfront] replaced {} directly", name);
        } catch (IOException e) {
            LOGGER.info("[Breakfront] {} locked -> staged for auto-apply", name);
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
