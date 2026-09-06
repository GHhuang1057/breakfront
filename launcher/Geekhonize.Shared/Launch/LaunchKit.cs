using System.Diagnostics;
using System.IO.Compression;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Text.Json;

namespace Geekhonize.Shared.Launch;

/// <summary>运行平台信息。</summary>
public static class HostOs
{
    public static bool IsWindows => RuntimeInformation.IsOSPlatform(OSPlatform.Windows);
    public static bool IsLinux => RuntimeInformation.IsOSPlatform(OSPlatform.Linux);
    public static bool IsOsx => RuntimeInformation.IsOSPlatform(OSPlatform.OSX);

    public static string NativesClassifier => IsWindows ? "natives-windows"
        : IsLinux ? "natives-linux" : "natives-osx";
    public static string JarExt => IsWindows ? ".jar" : ".jar"; // 兼容说明
    public static char PathSep => IsWindows ? ';' : ':';
    public static string CurrentRid => IsWindows ? "windows" : IsLinux ? "linux" : "osx";
}

/// <summary>BREAKFRONT 更新源（manifest → BF 客户端/core jar）。URL 模板未定前以记录/更新为限。</summary>
public sealed class BfFile
{
    public string Role { get; set; } = "";
    public string Sha256 { get; set; } = "";
    public long Size { get; set; }
    public string? Url { get; set; }
}

public sealed class BfManifest
{
    public List<BfFile> Files { get; set; } = new();
    public BfFile? Find(string role) => Files.FirstOrDefault(f => f.Role == role);
}

public static class BfSource
{
    public const string DefaultManifest = "https://mc.geekhonize.top/server/manifest.json";

    public static async Task<BfManifest?> FetchAsync(HttpClient http, string? manifestUrl = null)
    {
        var url = string.IsNullOrWhiteSpace(manifestUrl) ? DefaultManifest : manifestUrl!;
        var text = await http.GetStringAsync(url).ConfigureAwait(false);
        using var doc = JsonDocument.Parse(text);
        if (!doc.RootElement.TryGetProperty("files", out var fs)) return null;
        var m = new BfManifest();
        foreach (var f in fs.EnumerateArray())
        {
            var bf = new BfFile
            {
                Role = Get(f, "role") ?? "",
                Sha256 = Get(f, "sha256") ?? "",
            };
            bf.Size = f.TryGetProperty("size", out var sz) && sz.TryGetInt64(out var s) ? s : 0;
            m.Files.Add(bf);
        }
        return m;
    }

    private static string? Get(JsonElement e, string k)
        => e.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;
}

/// <summary>
/// B2 装配引擎：把标准布局实例目录装配成可启动的 Fabric 1.21.1 + BREAKFRONT 客户端。
/// 目录约定（自包含，非 PCL 混用）：
///   &lt;root&gt;/versions/1.21.1/        游戏 jar + 版本信息
///   &lt;root&gt;/libraries/…            Mojang 依赖（按 maven path 存）
///   &lt;root&gt;/assets/               资源（索引必须；对象按需/后台补齐）
///   &lt;root&gt;/natives/               解压的 LWJGL 原生库
///   &lt;root&gt;/mods/                  BREAKFRONT jar（manifest 各 role，URL 模板可用时）与第三方包
///   &lt;root&gt;/config/                客户端配置（host/port/updateport + auth.*）
/// </summary>
public sealed class LaunchKit
{
    private readonly HttpClient _http = new() { Timeout = TimeSpan.FromSeconds(30) };
    private string? _assetIndex;

    public const string McVersion = "1.21.1";
    public const string LoaderVersion = "0.19.5";
    public string RootDir { get; }

    public LaunchKit(string rootDir) => RootDir = rootDir;

    public event Action<string>? Log;
    private void Say(string s) => Log?.Invoke(s);

    private string GameJar => Path.Combine(RootDir, "versions", McVersion, $"{McVersion}.jar");

    /// <summary>完整装配：游戏本体(Fabric)+资源索引+原生库+BF 配置；不启动。</summary>
    public async Task AssembleAsync(bool skipClientFetch = false, IProgress<string>? progress = null)
    {
        Say($"装配目录 {RootDir}");
        Directory.CreateDirectory(Path.Combine(RootDir, "versions", McVersion));
        Directory.CreateDirectory(Path.Combine(RootDir, "libraries"));
        Directory.CreateDirectory(Path.Combine(RootDir, "assets"));
        Directory.CreateDirectory(Path.Combine(RootDir, "natives"));
        Directory.CreateDirectory(Path.Combine(RootDir, "mods"));
        Directory.CreateDirectory(Path.Combine(RootDir, "config"));

        // 1) Mojang 版本清单
        Say("读取 Mojang 版本清单…");
        var vmUrl = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
        var vm = JsonDocument.Parse(await _http.GetStringAsync(vmUrl));
        string? vJsonUrl = null;
        foreach (var v in vm.RootElement.GetProperty("versions").EnumerateArray())
            if (v.GetProperty("id").GetString() == McVersion) { vJsonUrl = v.GetProperty("url").GetString(); break; }
        if (vJsonUrl is null) throw new InvalidOperationException($"版本 {McVersion} 未在 Mojang 清单中");
        var vdoc = JsonDocument.Parse(await _http.GetStringAsync(vJsonUrl));

        // 2) 游戏 jar + 主依赖
        var dl = vdoc.RootElement.GetProperty("downloads");
        var cj = dl.GetProperty("client");
        var jarUrl = cj.GetProperty("url").GetString()!;
        await DownloadToAsync(jarUrl, GameJar, progress);

        // 3) libraries（artifact + natives classifier）
        int libCount = 0;
        foreach (var lib in vdoc.RootElement.GetProperty("libraries").EnumerateArray())
        {
            if (RuleBlocked(lib)) continue;
            if (!lib.TryGetProperty("downloads", out var d)) continue;
            if (d.TryGetProperty("artifact", out var art))
                await SaveArtifactAsync(art, libCount++, progress);
            if (d.TryGetProperty("classifiers", out var cls)
                && cls.TryGetProperty(HostOs.NativesClassifier, out var nat))
            {
                var p = nat.GetProperty("path").GetString()!;
                var f = Path.Combine(RootDir, "libraries", p.Replace('/', Path.DirectorySeparatorChar));
                await DownloadToAsync(nat.GetProperty("url").GetString()!, f, progress);
                if (p.EndsWith(".jar")) await ExtractNativesAsync(f);
            }
        }

        // 4) 资源索引
        var ai = vdoc.RootElement.GetProperty("assetIndex");
        _assetIndex = ai.GetProperty("id").GetString()!;
        Directory.CreateDirectory(Path.Combine(RootDir, "assets", "indexes"));
        var idxFile = Path.Combine(RootDir, "assets", "indexes", $"{_assetIndex}.json");
        if (!File.Exists(idxFile))
        {
            Say("下载资源索引 " + _assetIndex + "…");
            await DownloadToAsync(ai.GetProperty("url").GetString()!, idxFile, progress);
        }

        // 5) Fabric loader 启动库（meta）
        Say("读取 Fabric loader " + LoaderVersion + " 元数据…");
        var fmeta = $"https://meta.fabricmc.net/v2/versions/loader/{McVersion}/{LoaderVersion}/launcher/json";
        var fdoc = JsonDocument.Parse(await _http.GetStringAsync(fmeta));
        foreach (var lib in fdoc.RootElement.GetProperty("libraries").EnumerateArray())
        {
            var name = lib.GetProperty("name").GetString()!;
            var rel = MavenPath(name);
            var target = Path.Combine(RootDir, "libraries", rel);
            if (File.Exists(target)) continue;
            var url = lib.TryGetProperty("url", out var u) ? u.GetString() : null;
            await DownloadToAsync(url ?? "https://maven.fabricmc.net/" + rel.Replace('\\', '/'), target, progress);
        }

        // 6) 客户端配置
        WriteClientConfig();
        Say("装配完成：游戏本体 + Fabric " + LoaderVersion + " + 资源索引已就绪。");
    }

    private static bool RuleBlocked(JsonElement lib)
    {
        if (!lib.TryGetProperty("rules", out var rules)) return false;
        foreach (var r in rules.EnumerateArray())
        {
            var action = r.GetProperty("action").GetString(); // allow/disallow
            bool osMatch = true;
            if (r.TryGetProperty("os", out var os))
            {
                var want = os.TryGetProperty("name", out var n) ? n.GetString() : null;
                var have = HostOs.CurrentRid;
                var negate = os.TryGetProperty("negate", out var ng) && ng.GetBoolean();
                bool m = want == null
                    || have.StartsWith(want, StringComparison.OrdinalIgnoreCase);
                osMatch = m != negate;
            }
            bool featureOk = true; // 忽略 features（均视为命中）
            bool hit = osMatch && featureOk;
            if (hit && action == "disallow") return true;
            if (!hit && action == "allow") return true;
        }
        return false;
    }

    private async Task SaveArtifactAsync(JsonElement art, int i, IProgress<string>? p)
    {
        var path = art.GetProperty("path").GetString()!;
        var target = Path.Combine(RootDir, "libraries", path.Replace('/', Path.DirectorySeparatorChar));
        await DownloadToAsync(art.GetProperty("url").GetString()!, target, p);
    }

    private async Task ExtractNativesAsync(string jarFile)
    {
        try
        {
            using var z = ZipFile.OpenRead(jarFile);
            var natives = Path.Combine(RootDir, "natives");
            foreach (var e in z.Entries)
            {
                if (e.FullName.EndsWith("/") || e.FullName.Contains("META-INF")) continue;
                var name = Path.GetFileName(e.FullName);
                if (name.Length == 0) continue;
                var outPath = Path.Combine(natives, name);
                if (File.Exists(outPath)) continue;
                Directory.CreateDirectory(Path.GetDirectoryName(outPath)!);
                e.ExtractToFile(outPath, true);
            }
        }
        catch { /* 某些平台无 jar 原生库（非致命） */ }
    }

    private void WriteClientConfig()
    {
        var cfg = Path.Combine(RootDir, "config");
        var props = Path.Combine(cfg, "breakfront-client.properties");
        var lines = File.Exists(props) ? File.ReadAllLines(props).ToList() : new List<string>();
        void Set(string k, string v)
        {
            lines.RemoveAll(l => l.StartsWith(k + "="));
            lines.Add($"{k}={v}");
        }
        Set("host", "mc.geekhonize.top");
        Set("port", "25565");
        Set("updateport", "25610");
        File.WriteAllLines(props, lines);
        Say("客户端配置写入 " + props);
    }

    /// <summary>探测 Java（>=21 优先）：环境/常用目录/应用内 jre。</summary>
    public static string? DetectJava()
    {
        var candidates = new List<string>();
        var jh = Environment.GetEnvironmentVariable("JAVA_HOME");
        if (!string.IsNullOrWhiteSpace(jh))
            candidates.Add(Path.Combine(jh, "bin", HostOs.IsWindows ? "java.exe" : "java"));
        var baseDirs = new[] {
            Path.Combine(AppContext.BaseDirectory, "jre"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".bf_launcher", "jre")
        };
        foreach (var b in baseDirs)
            candidates.Add(Path.Combine(b, "bin", HostOs.IsWindows ? "java.exe" : "java"));
        candidates.Add(HostOs.IsWindows ? "java.exe" : "java");
        foreach (var c in candidates)
        {
            try { if (File.Exists(c)) return c; } catch { }
        }
        return "java"; // 交给 PATH
    }

    public static string? ProbeMajorVersion(string javaExe)
    {
        try
        {
            var psi = new ProcessStartInfo(javaExe, "-version") { RedirectStandardError = true, RedirectStandardOutput = true };
            using var p = Process.Start(psi);
            if (p == null) return null;
            var txt = p.StandardError.ReadToEnd() + p.StandardOutput.ReadToEnd();
            p.WaitForExit(4000);
            var m = System.Text.RegularExpressions.Regex.Match(txt, "\"([0-9]+)");
            if (m.Success && int.TryParse(m.Groups[1].Value, out var maj))
                return maj >= 8 ? m.Groups[1].Value : null; // 1.8 → 忽略
            return null;
        }
        catch { return null; }
    }

    /// <summary>构建启动命令（含 BREAKFRONT 会话键值）。</summary>
    public List<string> BuildCommand(string java, string username, string uuid, string? serverHost = null)
    {
        var cp = new List<string> { Path.Combine(RootDir, "versions", McVersion, $"{McVersion}.jar") };
        var libs = Path.Combine(RootDir, "libraries");
        foreach (var f in Directory.EnumerateFiles(libs, "*.jar", SearchOption.AllDirectories))
            cp.Add(f);
        var args = new List<string>
        {
            "-Xmx4G", "-Xms1G",
            "-Djava.library.path=" + Path.Combine(RootDir, "natives"),
            "-cp", string.Join(HostOs.PathSep, cp),
            "net.fabricmc.loader.impl.launch.knot.KnotClient",
            "--gameDir", RootDir,
            "--assetsDir", Path.Combine(RootDir, "assets"),
            "--assetIndex", _assetIndex ?? "19",
            "--uuid", uuid,
            "--accessToken", "0",
            "--userType", "legacy",
            "--version", McVersion
        };
        if (!string.IsNullOrWhiteSpace(username)) { args.Add("--username"); args.Add(username); }
        return args;
    }

    /// <summary>以目标 Java 启动游戏进程（不等待）。</summary>
    public Process? Launch(string java, List<string> args)
    {
        var psi = new ProcessStartInfo(java)
        {
            WorkingDirectory = RootDir,
            UseShellExecute = false,
        };
        foreach (var a in args) psi.ArgumentList.Add(a);
        try { return Process.Start(psi); } catch (Exception e) { Say("启动失败：" + e.Message); return null; }
    }

    private async Task DownloadToAsync(string url, string dest, IProgress<string>? p)
    {
        if (File.Exists(dest) && new FileInfo(dest).Length > 0) return;
        Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
        Say("下载 " + Path.GetFileName(dest) + " …");
        using var resp = await _http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead);
        resp.EnsureSuccessStatusCode();
        await using var src = await resp.Content.ReadAsStreamAsync();
        await using var dst = File.Create(dest + ".tmp");
        var buf = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = await src.ReadAsync(buf.AsMemory(0, buf.Length))) > 0)
        {
            await dst.WriteAsync(buf.AsMemory(0, n));
            total += n;
            p?.Report($"{Path.GetFileName(dest)}  {total / 1024} KB");
        }
        File.Move(dest + ".tmp", dest, true);
    }

    private static string MavenPath(string name)
    {
        // group:artifact:version[:classifier]
        var parts = name.Split(':');
        var (g, a, v) = (parts[0], parts[1], parts[2]);
        var cls = parts.Length > 3 && parts[3].Length > 0 ? "-" + parts[3] : "";
        return $"{g.Replace('.', '/')}/{a}/{v}/{a}-{v}{cls}.jar";
    }

    public void Dispose() => _http.Dispose();
}
