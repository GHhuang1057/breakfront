using System.Diagnostics;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Geekhonize.Shared.Launch;

/// <summary>BREAKFRONT 启动计划：实例目录 + Java + 游戏参数。后续 B2 接入整合包下载/自更新。</summary>
public sealed class LaunchPlan
{
    /// <summary>启动器版本（与 client 更新源/服务端协调用）。</summary>
    public const string Version = "0.1.0";

    public string InstanceDir { get; set; } = "";     // .minecraft 同级实例目录（Fabric 客户端根）
    public string JavaPath { get; set; } = "java";    // 可执行 Java（B2 自动寻址）
    public string Username { get; set; } = "";        // 离线游戏名（服务端离线模式 + 账号绑定由 GeoSession 承担）
    public string Uuid { get; set; } = "";            // 离线 UUID（留空自动派生自用户名）
    public string ServerAddress { get; set; } = "";   // 目标服（默认走客户端内置）

    /// <summary>
    /// 启动 Fabric 客户端所需的最小参数模板。B1 仅做计划输出与冒烟；
    /// 具体 JVM 参数/类路径由 B2 的整合包解析器填充。
    /// </summary>
    public IReadOnlyList<string> BuildArguments(string gameDir)
    {
        var args = new List<string>
        {
            "-Xmx4G", "-Xms1G",
            "-Djava.library.path=" + Path.Combine(gameDir, "natives"),
            "-cp", Path.Combine(gameDir, "fabric-loader.jar"),
            "net.fabricmc.loader.impl.launch.knot.KnotClient",
            "--gameDir", gameDir,
            "--assetsDir", Path.Combine(gameDir, "assets"),
            "--version", "breakfront-1.21.1"
        };
        if (!string.IsNullOrWhiteSpace(Username)) { args.Add("--username"); args.Add(Username); }
        if (!string.IsNullOrWhiteSpace(Uuid)) { args.Add("--uuid"); args.Add(Uuid); }
        return args;
    }

    /// <summary>尝试探测系统 Java（PATH / JAVA_HOME）。B2 扩展。</summary>
    public static string DetectJava()
    {
        var jh = Environment.GetEnvironmentVariable("JAVA_HOME");
        if (!string.IsNullOrWhiteSpace(jh))
        {
            var bin = Path.Combine(jh, "bin", OperatingSystem.IsWindows() ? "java.exe" : "java");
            if (File.Exists(bin)) return bin;
        }
        return "java";
    }
}
