using System.Security.Cryptography;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;
using Geekhonize.Shared.Auth;
using Geekhonize.Shared.Launch;
using Geekhonize.Shared.Session;

namespace Geekhonize.App.Views;

/// <summary>BREAKFRONT 专属启动器 · 主窗口（登录/注册 + B2 装配与启动）。</summary>
public sealed class LoginWindow : Window
{
    private readonly AuthClient _auth = new();
    private readonly TextBox _user = new() { Watermark = "Geekhonize 用户名" };
    private readonly TextBox _pass = new() { Watermark = "密码（注册需 ≥8 位）" };
    private readonly TextBox _email = new() { Watermark = "邮箱（接收验证码）" };
    private readonly TextBox _code = new() { Watermark = "6 位邮箱验证码" };
    private readonly Button _sendCode = new() { Content = "发送验证码" };
    private readonly TextBox _mcDir = new() { Watermark = "兼容目录(.minecraft，写会话)" };
    private readonly TextBox _root = new() { Watermark = "BREAKFRONT 装配根（B2 自包含实例）" };
    private readonly TextBlock _status = new() { TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock _log = new() { TextWrapping = TextWrapping.Wrap };
    private readonly Button _go = new() { Content = "装配并启动游戏", HorizontalAlignment = HorizontalAlignment.Stretch };
    private readonly Button _only = new() { Content = "仅装配（不启动）", HorizontalAlignment = HorizontalAlignment.Stretch };
    private readonly Button _login = new() { Content = "登 录（写会话）", HorizontalAlignment = HorizontalAlignment.Stretch };
    private readonly Button _toggle = new()
    {
        Content = "没有账号？邮箱验证码注册",
        HorizontalAlignment = HorizontalAlignment.Stretch,
        Background = Avalonia.Media.Brushes.Transparent,
    };
    private bool _busy;
    private bool _registerMode;
    private DispatcherTimer? _cdTimer;
    private int _cdLeft;

    public LoginWindow()
    {
        Title = "BREAKFRONT 启动器 · Geekhonize 登录 + 装配";
        Width = 560;
        Height = 780;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;

        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        _mcDir.Text = Path.Combine(home, ".minecraft");
        _root.Text = Path.Combine(home, "BREAKFRONT");

        var panel = new StackPanel { Margin = new Avalonia.Thickness(24), Spacing = 8 };
        panel.Children.Add(new TextBlock { Text = "BREAKFRONT · 破阵前线 专属启动器",
            FontSize = 20, FontWeight = FontWeight.Bold, HorizontalAlignment = HorizontalAlignment.Center });
        panel.Children.Add(new TextBlock { Text = "v" + LaunchPlan.Version + " · 登录 Geekhonize → 装配 Fabric 1.21.1 → 一键开战",
            Foreground = Brushes.Gray, HorizontalAlignment = HorizontalAlignment.Center });

        panel.Children.Add(new TextBlock { Text = "用户名" });
        panel.Children.Add(_user);
        panel.Children.Add(new TextBlock { Text = "密码" });
        panel.Children.Add(_pass);

        // 注册模式专属：邮箱 + 验证码（默认隐藏）
        _email.IsVisible = _code.IsVisible = _sendCode.IsVisible = false;
        panel.Children.Add(new TextBlock { Text = "邮箱（注册用，接收验证码）", IsVisible = false, Tag = "lblEmail" });
        panel.Children.Add(_email);
        var codeRow = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, IsVisible = false, Tag = "codeRow" };
        _code.HorizontalAlignment = HorizontalAlignment.Stretch;
        _code.Width = 260;
        codeRow.Children.Add(_code);
        codeRow.Children.Add(_sendCode);
        panel.Children.Add(codeRow);

        panel.Children.Add(new TextBlock { Text = "客户端会话目录（登录写 <目录>/config/breakfront-client.properties，兼容现 PCL）" });
        panel.Children.Add(_mcDir);
        panel.Children.Add(new TextBlock { Text = "BREAKFRONT 装配根（首次装配下载游戏本体与依赖，剩余空间建议 ≥3GB）" });
        panel.Children.Add(_root);
        panel.Children.Add(_login);
        panel.Children.Add(_toggle);
        panel.Children.Add(_go);
        panel.Children.Add(_only);
        panel.Children.Add(_status);
        panel.Children.Add(new TextBlock { Text = "装配日志：" });
        _log.Text = "尚未运行。装配需联网（Mojang/Fabric/本站）。";
        panel.Children.Add(_log);

        Content = new ScrollViewer { Content = panel };

        _login.Click += async (_, _) => { if (_registerMode) await DoRegisterAsync(); else await DoLoginAsync(); };
        _toggle.Click += (_, _) => SetMode(!_registerMode);
        _sendCode.Click += async (_, _) => await SendCodeAsync();
        _go.Click += async (_, _) => await RunKitAsync(true);
        _only.Click += async (_, _) => await RunKitAsync(false);

        try
        {
            var s = GeoSessionFile.Load(Path.Combine(_mcDir.Text, "config"));
            if (!string.IsNullOrEmpty(s.Username)) _user.Text = s.Username;
            _status.Text = s.Token.Length > 0 ? "检测到会话：" + s.Username + "（进服自动绑定）"
                : "未登录：请先登录，或切换到注册模式创建账号。";
        }
        catch { }
    }

    private void SetMode(bool register)
    {
        _registerMode = register;
        _email.IsVisible = _code.IsVisible = _sendCode.IsVisible = register;
        foreach (var child in ((StackPanel)((ScrollViewer)Content).Content).Children)
        {
            if (child.Tag as string == "lblEmail" || child.Tag as string == "codeRow")
                child.IsVisible = register;
        }
        _login.Content = register ? "注 册（并登录写会话）" : "登 录（写会话）";
        _toggle.Content = register ? "← 已有账号？返回登录" : "没有账号？邮箱验证码注册";
    }

    private async Task SendCodeAsync()
    {
        var email = _email.Text?.Trim() ?? "";
        if (!email.Contains('@') || !email.Contains('.'))
        {
            _status.Text = "请先填写有效邮箱再发送验证码。";
            return;
        }
        _sendCode.IsEnabled = false;
        _status.Text = "验证码发送中…";
        try
        {
            await _auth.SendCodeAsync(email, "register");
            _status.Text = "✓ 验证码已发送至 " + email + "（10 分钟内有效，请查收邮箱/垃圾箱）。";
            StartCooldown(60);
        }
        catch (Exception e) { _status.Text = "验证码发送失败：" + e.Message; _sendCode.IsEnabled = true; }
    }

    private void StartCooldown(int secs)
    {
        _cdLeft = secs;
        _cdTimer?.Stop();
        _cdTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _cdTimer.Tick += (_, _) =>
        {
            _cdLeft--;
            if (_cdLeft <= 0) { _cdTimer!.Stop(); _sendCode.Content = "重新发送"; _sendCode.IsEnabled = true; }
            else _sendCode.Content = _cdLeft + "s";
        };
        _sendCode.Content = secs + "s";
        _cdTimer.Start();
    }

    private async Task DoRegisterAsync()
    {
        var u = _user.Text?.Trim() ?? "";
        var p = _pass.Text ?? "";
        var email = _email.Text?.Trim() ?? "";
        var code = _code.Text?.Trim() ?? "";
        if (u.Length < 3) { _status.Text = "用户名至少 3 位（字母/数字/_-.）。"; return; }
        if (p.Length < 8) { _status.Text = "密码至少 8 位。"; return; }
        if (email.Length == 0 || code.Length == 0) { _status.Text = "注册需填写邮箱与验证码。"; return; }

        _login.IsEnabled = false;
        _status.Text = "注册中…";
        try
        {
            var r = await _auth.RegisterAsync(u, email, code, p);
            if (!r.Ok || r.AccessToken == null) { _status.Text = "注册失败：" + (r.Message ?? ""); return; }
            OnSignedIn(r);
        }
        catch (Exception e) { _status.Text = "注册失败：" + e.Message; }
        finally { _login.IsEnabled = true; }
    }

    private async Task DoLoginAsync()
    {
        _login.IsEnabled = false;
        _status.Text = "登录中…";
        try
        {
            var r = await _auth.LoginAsync(_user.Text?.Trim() ?? "", _pass.Text ?? "");
            if (!r.Ok || r.AccessToken == null) { _status.Text = "登录失败：" + (r.Message ?? ""); return; }
            OnSignedIn(r);
        }
        catch (Exception e) { _status.Text = "登录失败：" + e.Message; }
        finally { _login.IsEnabled = true; }
    }

    private void OnSignedIn(AuthClient.LoginResult r)
    {
        var u = r.User?.Username ?? "";
        new GeoSessionFile { Token = r.AccessToken!, Username = u }.Save(Path.Combine(_mcDir.Text, "config"));
        new GeoSessionFile { Token = r.AccessToken!, Username = u }.Save(Path.Combine(_root.Text, "config"));
        _status.Text = "✓ 已登录：" + u + "（角色 " + string.Join(",", r.User?.Roles ?? Array.Empty<string>()) + "）\n会话已写入两处配置。";
    }

    private void PostLog(string line)
    {
        Dispatcher.UIThread.Post(() =>
        {
            var parts = (_log.Text + "\n" + line).Split('\n');
            if (parts.Length > 60) parts = parts[^60..];
            _log.Text = string.Join('\n', parts);
        });
    }

    private async Task RunKitAsync(bool launch)
    {
        if (_busy) return;
        var s = GeoSessionFile.Load(Path.Combine(_root.Text, "config"));
        if (string.IsNullOrEmpty(s.Token)) { _status.Text = "请先在启动器登录（账号会话需写入装配根）"; return; }
        _busy = true; _go.IsEnabled = _only.IsEnabled = false;
        _log.Text = "";
        try
        {
            var me = await _auth.MeAsync(s.Token);
            if (!me.Ok || me.User == null) { _status.Text = "会话失效：" + (me.Message ?? "") + "，请重新登录"; return; }
            var root = _root.Text?.Trim() ?? "";
            Directory.CreateDirectory(root);

            var java = LaunchKit.DetectJava();
            var ver = LaunchKit.ProbeMajorVersion(java ?? "java");
            PostLog("Java 探测：" + java + "  major=" + (ver ?? "?")
                + (ver != null && int.Parse(ver) >= 21 ? "（OK）" : "（需 ≥21，请装 Temurin 21 或把 java 加入 PATH）"));

            using var kit = new LaunchKit(root);
            kit.Log += PostLog;
            await kit.AssembleAsync();
            if (!launch) { _status.Text = "装配完成（未启动）。"; return; }

            if (ver == null || int.Parse(ver) < 21) { _status.Text = "Java < 21，请安装 Java 21 后重试。"; return; }
            var uuid = OfflineUuid(me.User.Username);
            var args = kit.BuildCommand(java!, me.User.Username, uuid);
            PostLog("启动：" + java + " " + string.Join(' ', args.Take(5)) + " …");
            var proc = kit.Launch(java!, args);
            _status.Text = proc == null ? "启动失败（见日志）" : "✓ 游戏进程已启动（PID " + proc.Id + "）。关闭游戏即退出。";
        }
        catch (Exception e)
        {
            _status.Text = "装配/启动出错：" + e.Message;
            PostLog("错误：" + e);
        }
        finally { _busy = false; _go.IsEnabled = _only.IsEnabled = true; }
    }

    private static string OfflineUuid(string name)
    {
        var bytes = MD5.HashData(System.Text.Encoding.UTF8.GetBytes("OfflinePlayer:" + name));
        bytes[6] = (byte)((bytes[6] & 0x0f) | 0x30); // v3
        bytes[8] = (byte)((bytes[8] & 0x3f) | 0x80);
        return new Guid(bytes).ToString("N");
    }
}
