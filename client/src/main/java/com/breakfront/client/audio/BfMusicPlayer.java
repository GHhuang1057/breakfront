package com.breakfront.client.audio;

import com.breakfront.client.state.ClientMatchState;
import com.breakfront.client.state.ClientMatchState.FriendDot;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.openal.AL10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * BF 情境音乐播放器（2026-09-06 v1，方案 B 全量外置音频的播放端）：
 * - 音频文件由启动预检同步至 gameDir/bfmusic/<scene>/*.mp3；
 * - 本类在后台线程用 JLayer 解码 mp3 → PCM，主线程经 OpenAL 循环播放；
 * - 场景随对局阶段自动切换：LOBBY→lobby / COUNTDOWN+BATTLE→battle /
 *   ROUND_END→win|lose（按我方阵营与结果）；
 * - 配置（breakfront-client.properties）：music.enabled=true 默认开、
 *   music.volume=0.42；无曲目或解码失败一律静默降级，不影响游戏。
 */
public final class BfMusicPlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("breakfront.music");
    private static final Random RNG = new Random();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bf-music-decode");
        t.setDaemon(true);
        return t;
    });

    /** 当前播放源/缓冲（仅主线程操作）。 */
    private static int source = -1;
    private static int buffer = -1;
    /** 当前播放键（"scene:曲名"；空=未播）。 */
    private static String playingKey = "";
    /** 切换下发中（防每秒重复触发）。 */
    private static volatile boolean inFlight;
    /** 已确认某场景曲库为空（30s 内不重扫）。 */
    private static String emptyScene = "";
    private static long emptyAtMs;
    private static long nextEvalMs;

    private BfMusicPlayer() {
    }

    /** 主线程低频驱动（每秒评估一次；由 BreakfrontClient 注册）。 */
    public static void tick(MinecraftClient client) {
        long now = System.currentTimeMillis();
        if (now < nextEvalMs) {
            return;
        }
        nextEvalMs = now + 1000;
        if (!enabled()) {
            if (!playingKey.isEmpty()) {
                stopPlayback();
                playingKey = "";
                inFlight = false;
            }
            return;
        }
        // 不在世界或未连上 BF 服务器 → 停播（单人/主菜单静音）
        if (client == null || client.world == null || !ClientMatchState.hasLiveMatch()) {
            if (!playingKey.isEmpty()) {
                stopPlayback();
                playingKey = "";
                inFlight = false;
            }
            return;
        }
        String scene = sceneFor(client);
        if (scene == null) {
            return;
        }
        // 已在该场景播放 → 不动
        if (!playingKey.isEmpty() && playingKey.startsWith(scene + ":")) {
            return;
        }
        startScene(scene);
    }

    /** 当前对局阶段 → 曲库场景目录。 */
    private static String sceneFor(MinecraftClient client) {
        int ph = ClientMatchState.phaseOrdinal();
        if (ph <= 0) {
            return "lobby";
        }
        if (ph == 1 || ph == 2) {
            return "battle"; // COUNTDOWN / BATTLE
        }
        if (ph == 3) {
            // ROUND_END：我方阵营胜负 → win / lose
            int res = ClientMatchState.lastResultOrdinal(); // NONE=0 ATTACKER_WIN=1 DEFENDER_WIN=2
            int mySide = mySide(client);
            boolean won = res == 1 ? mySide == 0 : res == 2 ? mySide == 1 : true;
            return won ? "win" : "lose";
        }
        return "lobby";
    }

    private static int mySide(MinecraftClient client) {
        if (client.player == null) {
            return -1;
        }
        String me = client.player.getName().getString();
        for (FriendDot f : ClientMatchState.friends()) {
            if (f.name().equals(me)) {
                return f.sideOrdinal();
            }
        }
        return -1;
    }

    /** 启动某场景（异步解码，完成后主线程换源）。 */
    private static void startScene(String scene) {
        if (inFlight) {
            return;
        }
        // 场景曲库空（30s 缓存），跳过避免反复扫描
        if (scene.equals(emptyScene) && System.currentTimeMillis() - emptyAtMs < 30000) {
            return;
        }
        List<Path> songs = listMp3(scene);
        if (songs.isEmpty()) {
            emptyScene = scene;
            emptyAtMs = System.currentTimeMillis();
            LOGGER.info("[BF-music] scene '{}' empty (server not uploaded yet)", scene);
            return;
        }
        emptyScene = "";
        Path pick = songs.get(RNG.nextInt(songs.size()));
        String key = scene + ":" + pick.getFileName().toString();
        if (key.equals(playingKey)) {
            return;
        }
        inFlight = true;
        final Path file = pick;
        final String want = key;
        final String wantScene = scene;
        IO.execute(() -> {
            try {
                Decoded d = decodeMp3(file);
                MinecraftClient.getInstance().execute(() -> applyPlayback(wantScene, want, d));
            } catch (Exception e) {
                LOGGER.warn("[BF-music] decode {} failed: {}", pick.getFileName(), e.toString());
                inFlight = false;
            }
        });
    }

    private record Decoded(byte[] pcm, int freq, int channels) {
    }

    /** JLayer 解码 mp3 → PCM16 LE（后台线程）。 */
    private static Decoded decodeMp3(Path file) throws Exception {
        Bitstream bs = null;
        try {
            bs = new Bitstream(new BufferedInputStream(new FileInputStream(file.toFile())));
            Decoder dec = new Decoder();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(1 << 20);
            int freq = -1;
            int channels = -1;
            Header h;
            while ((h = bs.readFrame()) != null) {
                SampleBuffer sb = (SampleBuffer) dec.decodeFrame(h, bs);
                short[] pcm = sb.getBuffer();
                int len = sb.getBufferLength();
                for (int i = 0; i < len; i++) {
                    short s = pcm[i];
                    out.write(s & 0xFF);
                    out.write((s >> 8) & 0xFF);
                }
                if (freq < 0) {
                    freq = dec.getOutputFrequency();
                    channels = dec.getOutputChannels();
                }
                bs.closeFrame();
            }
            if (freq <= 0) {
                throw new IOException("no audio frame decoded");
            }
            return new Decoded(out.toByteArray(), freq, channels);
        } finally {
            if (bs != null) {
                try {
                    bs.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 主线程提交缓冲并循环播放（换源前停旧源）。 */
    private static void applyPlayback(String scene, String key, Decoded d) {
        try {
            if (key.equals(playingKey)) {
                return;
            }
            stopPlayback();
            int fmt = d.channels() >= 2 ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16;
            int b = AL10.alGenBuffers();
            ByteBuffer bb = ByteBuffer.allocateDirect(d.pcm().length);
            bb.put(d.pcm()).flip();
            AL10.alBufferData(b, fmt, bb, d.freq());
            int s = AL10.alGenSources();
            AL10.alSourcei(s, AL10.AL_LOOPING, AL10.AL_TRUE);
            AL10.alSourcei(s, AL10.AL_BUFFER, b);
            AL10.alSourcef(s, AL10.AL_GAIN, volume());
            AL10.alSourcePlay(s);
            int err = AL10.alGetError();
            if (err != AL10.AL_NO_ERROR) {
                throw new IllegalStateException("OpenAL err 0x" + Integer.toHexString(err));
            }
            source = s;
            buffer = b;
            playingKey = key;
            LOGGER.info("[BF-music] playing {} ({}Hz ch{})", key, d.freq(), d.channels());
        } catch (Exception e) {
            LOGGER.warn("[BF-music] apply failed: {}", e.toString());
            stopPlayback();
            playingKey = "";
        } finally {
            inFlight = false;
        }
    }

    private static void stopPlayback() {
        if (source >= 0) {
            try {
                AL10.alSourceStop(source);
                AL10.alDeleteSources(source);
            } catch (Exception ignored) {
            }
            source = -1;
        }
        if (buffer >= 0) {
            try {
                AL10.alDeleteBuffers(buffer);
            } catch (Exception ignored) {
            }
            buffer = -1;
        }
    }

    /** 列出 gameDir/bfmusic/<scene>/*.mp3。 */
    private static List<Path> listMp3(String scene) {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("bfmusic").resolve(scene);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".mp3"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(Collectors.toCollection(ArrayList::new));
        } catch (IOException e) {
            return List.of();
        }
    }

    // ---------- 配置 ----------

    private static boolean enabled() {
        String v = prop("music.enabled");
        return v == null || !v.equalsIgnoreCase("false");
    }

    private static float volume() {
        String v = prop("music.volume");
        if (v != null) {
            try {
                return Math.max(0.05f, Math.min(1.0f, Float.parseFloat(v.trim())));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0.42f;
    }

    private static String prop(String key) {
        try {
            Path p = FabricLoader.getInstance().getConfigDir().resolve("breakfront-client.properties");
            if (!Files.isRegularFile(p)) {
                return null;
            }
            for (String line : Files.readAllLines(p)) {
                int e = line.indexOf('=');
                if (e > 0 && line.substring(0, e).trim().equals(key)) {
                    return line.substring(e + 1).trim();
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }
}
