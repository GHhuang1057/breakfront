package com.breakfront.client.upd;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 外置音频库同步（B 方案，2026-09-05 v1）：
 * 启动预检阶段从更新源拉取 /breakfront/music/music.json（场景分目录的 mp3/ogg 清单），
 * 与本地 gameDir/bfmusic/<scene>/ 比对 sha256，缺/旧则差量下载，全程报告进度。
 * 播放器（mp3 解码 → OpenAL）为独立后续窗口，本类只负责把音频就位。
 */
public final class MusicUpdater {

    private static final Logger LOGGER = LoggerFactory.getLogger("breakfront.music");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private MusicUpdater() {
    }

    public record MusicFile(String name, String scene, String sha256, int size) {
    }

    public record SyncResult(int downloaded, int total, long bytes, String msg) {
    }

    /** 进度回调（与 {@link Updater.Progress} 同形，复用其定义）。 */
    public static SyncResult sync(String host, int updatePort, Updater.Progress progress) {
        String base = "http://" + host + ":" + updatePort + "/breakfront/music";
        List<MusicFile> remote = new ArrayList<>();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/music.json"))
                    .timeout(Duration.ofSeconds(6)).GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return new SyncResult(0, 0, 0, "音频清单不可用（http " + resp.statusCode() + "）");
            }
            remote = parseManifest(resp.body());
        } catch (Exception e) {
            return new SyncResult(0, 0, 0, "音频源不可达");
        }
        if (remote.isEmpty()) {
            return new SyncResult(0, 0, 0, "音频库为空（运营尚未上传曲目）");
        }
        Path musicRoot = FabricLoader.getInstance().getGameDir().resolve("bfmusic");
        List<MusicFile> need = new ArrayList<>();
        for (MusicFile mf : remote) {
            Path f = musicRoot.resolve(mf.scene()).resolve(mf.name());
            boolean outdated = !Files.isRegularFile(f)
                    || !mf.sha256().equals(sha256File(f));
            if (outdated) {
                need.add(mf);
            }
        }
        if (need.isEmpty()) {
            if (progress != null) {
                progress.report("音频已是最新", 1.0f);
            }
            return new SyncResult(0, remote.size(), 0, "音频已是最新");
        }
        int ok = 0;
        long bytes = 0;
        int done = 0;
        for (MusicFile mf : need) {
            if (progress != null) {
                progress.report("正在下载音频…（" + (done + 1) + "/" + need.size() + "）",
                        0.05f + 0.95f * done / need.size());
            }
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/files/"
                        + mf.scene() + "/" + mf.name()))
                        .timeout(Duration.ofSeconds(60)).GET().build();
                HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() == 200 && mf.sha256().equals(sha256(resp.body()))) {
                    Path dir = musicRoot.resolve(mf.scene());
                    Files.createDirectories(dir);
                    Files.write(dir.resolve(mf.name()), resp.body());
                    ok++;
                    bytes += resp.body().length;
                }
            } catch (Exception e) {
                LOGGER.warn("[Breakfront] music {} download failed: {}", mf.name(), e.toString());
            }
            done++;
        }
        String msg = ok == need.size()
                ? "音频就绪（" + ok + " 首）"
                : "音频部分下载（" + ok + "/" + need.size() + "）";
        if (progress != null) {
            progress.report(msg, 1.0f);
        }
        return new SyncResult(ok, need.size(), bytes, msg);
    }

    // ---- 极简 manifest 解析（与 Updater.parseManifest 同风格） ----

    private static List<MusicFile> parseManifest(String body) {
        List<MusicFile> out = new ArrayList<>();
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
            String scene = quoted(obj, "scene");
            String sha = quoted(obj, "sha256");
            int size = intQuoted(obj, "size");
            if (name != null && scene != null && sha != null) {
                out.add(new MusicFile(name, scene, sha, size));
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
        int q1 = obj.indexOf('"', c);
        int q2 = obj.indexOf('"', q1 + 1);
        if (q1 < 0 || q2 < 0) {
            return null;
        }
        return obj.substring(q1 + 1, q2);
    }

    private static int intQuoted(String obj, String key) {
        int k = obj.indexOf('"' + key + '"');
        if (k < 0) {
            return 0;
        }
        int c = obj.indexOf(':', k);
        if (c < 0) {
            return 0;
        }
        int e = c + 1;
        while (e < obj.length() && Character.isDigit(obj.charAt(e))) {
            e++;
        }
        try {
            return Integer.parseInt(obj.substring(c + 1, e));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String sha256File(Path p) {
        try {
            return sha256(Files.readAllBytes(p));
        } catch (Exception e) {
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
}
