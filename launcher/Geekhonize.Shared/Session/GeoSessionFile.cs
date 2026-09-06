namespace Geekhonize.Shared.Session;

/// <summary>
/// 与 BREAKFRONT 客户端同格式的本地会话文件（config/breakfront-client.properties）。
/// 启动器登录成功后预写 auth.token / auth.username，客户端进服自动绑定（零改动兼容）。
/// </summary>
public sealed class GeoSessionFile
{
    public string Token { get; set; } = "";
    public string Username { get; set; } = "";

    public static GeoSessionFile Load(string configDir)
    {
        var s = new GeoSessionFile();
        try
        {
            var p = Path.Combine(configDir, "breakfront-client.properties");
            if (!File.Exists(p)) return s;
            foreach (var line in File.ReadAllLines(p))
            {
                var i = line.IndexOf('=');
                if (i <= 0) continue;
                var k = line[..i].Trim();
                var v = line[(i + 1)..].Trim();
                if (k == "auth.token") s.Token = v;
                else if (k == "auth.username") s.Username = v;
            }
        }
        catch { /* 读失败视为空会话 */ }
        return s;
    }

    /// <summary>行级保留其它配置键，只更新 auth.*。</summary>
    public void Save(string configDir)
    {
        try
        {
            Directory.CreateDirectory(configDir);
            var p = Path.Combine(configDir, "breakfront-client.properties");
            var lines = File.Exists(p) ? File.ReadAllLines(p).ToList() : new List<string>();
            lines.RemoveAll(l => l.StartsWith("auth.token=") || l.StartsWith("auth.username="));
            lines.Add("auth.token=" + Token);
            lines.Add("auth.username=" + Username);
            File.WriteAllLines(p, lines);
        }
        catch { /* 写失败不阻塞启动器主流程 */ }
    }
}
