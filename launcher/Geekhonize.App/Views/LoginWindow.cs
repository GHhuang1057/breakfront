using Avalonia.Controls;
using Avalonia.Layout;
using Geekhonize.Shared.Auth;
using Geekhonize.Shared.Launch;
using Geekhonize.Shared.Session;

namespace Geekhonize.App.Views;

/// <summary>BREAKFRONT 专属启动器 · 登录窗（Geekhonize 账号 → 会话写入客户端配置）。</summary>
public sealed class LoginWindow : Window
{
    private readonly AuthClient _auth = new();
    private readonly TextBox _user = new() { Watermark = "Geekhonize 用户名" };
    private readonly TextBox _pass = new() { Watermark = "密码" };
    private readonly TextBox _instanceDir = new() { Watermark = "游戏实例目录（含 config/）" };
    private readonly TextBlock _status = new() { TextWrapping = Avalonia.Media.TextWrapping.Wrap };
    private readonly Button _login = new() { Content = "登 录", HorizontalAlignment = HorizontalAlignment.Stretch };
    private readonly Button _demo = new() { Content = "校验本地会话（演示）", HorizontalAlignment = HorizontalAlignment.Stretch };

    public LoginWindow()
    {
        Title = "BREAKFRONT 启动器 · Geekhonize 登录";
        Width = 460;
        Height = 560;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;

        var defaultInstance = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".minecraft");
        _instanceDir.Text = defaultInstance;

        var panel = new StackPanel { Margin = new Avalonia.Thickness(28), Spacing = 10 };
        panel.Children.Add(new TextBlock
        {
            Text = "BREAKFRONT · 破阵前线",
            FontSize = 20,
            FontWeight = Avalonia.Media.FontWeight.Bold,
            HorizontalAlignment = HorizontalAlignment.Center
        });
        panel.Children.Add(new TextBlock
        {
            Text = "专属启动器 v" + LaunchPlan.Version + "（A1 登录 · B2 客户端装配）",
            Foreground = Avalonia.Media.Brushes.Gray,
            HorizontalAlignment = HorizontalAlignment.Center
        });
        panel.Children.Add(new TextBlock { Text = "用户名" });
        panel.Children.Add(_user);
        panel.Children.Add(new TextBlock { Text = "密码" });
        panel.Children.Add(_pass);
        panel.Children.Add(new TextBlock { Text = "游戏实例目录（会话写入 <目录>/config/breakfront-client.properties）" });
        panel.Children.Add(_instanceDir);
        panel.Children.Add(_login);
        panel.Children.Add(_demo);
        panel.Children.Add(_status);

        Content = panel;
        _login.Click += async (_, _) => await DoLoginAsync();
        _demo.Click += async (_, _) => await DoDemoAsync();

        try
        {
            var s = GeoSessionFile.Load(Path.Combine(defaultInstance, "config"));
            if (!string.IsNullOrEmpty(s.Username)) { _user.Text = s.Username; }
            _status.Text = s.Token.Length > 0
                ? "检测到已登录会话：" + s.Username + "（进服自动绑定）"
                : "未登录。注册需邮箱验证码（网页端 auth.geekhonize.top 更佳）。";
        }
        catch { }
    }

    private async Task DoLoginAsync()
    {
        _login.IsEnabled = false;
        _status.Text = "登录中…";
        try
        {
            var r = await _auth.LoginAsync(_user.Text?.Trim() ?? "", _pass.Text ?? "");
            if (!r.Ok || r.AccessToken == null)
            {
                _status.Text = "登录失败：" + (r.Message ?? "未知错误");
                return;
            }
            var cfg = Path.Combine(_instanceDir.Text?.Trim() ?? "", "config");
            new GeoSessionFile { Token = r.AccessToken, Username = r.User?.Username ?? "" }.Save(cfg);
            _status.Text = "✓ 登录成功：" + r.User?.Username
                + "（" + string.Join(", ", r.User?.Roles ?? Array.Empty<string>()) + "）\n"
                + "会话已写入：" + cfg + "\n重启/进入游戏即自动绑定。";
        }
        catch (AuthApiException e) { _status.Text = "登录失败：" + e.Message; }
        catch (Exception e) { _status.Text = "网络/解析错误：" + e.Message; }
        finally { _login.IsEnabled = true; }
    }

    private async Task DoDemoAsync()
    {
        _demo.IsEnabled = false;
        _status.Text = "校验本地会话…";
        try
        {
            var cfg = Path.Combine(_instanceDir.Text?.Trim() ?? "", "config");
            var s = GeoSessionFile.Load(cfg);
            if (string.IsNullOrEmpty(s.Token)) { _status.Text = "尚无会话，请先登录。"; return; }
            var me = await _auth.MeAsync(s.Token);
            if (!me.Ok || me.User == null)
            {
                _status.Text = "令牌无效或已过期：" + (me.Message ?? "") + "\n请重新登录。";
                return;
            }
            var plan = new LaunchPlan { InstanceDir = cfg, Username = me.User.Username };
            _status.Text = "✓ 会话有效：" + me.User.Username + "（" + me.User.Email + "）\n"
                + "Java 探测：" + LaunchPlan.DetectJava() + "\n"
                + "（B2 将在此接入 Fabric 客户端整合包装配与启动）";
        }
        catch (Exception e) { _status.Text = "错误：" + e.Message; }
        finally { _demo.IsEnabled = true; }
    }
}
