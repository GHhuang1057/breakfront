using Avalonia;
using Avalonia.Android;

namespace Geekhonize.Android;

/// <summary>入口 Activity（manifest 已声明 Main/Launcher）。</summary>
public class MainActivity : AvaloniaMainActivity<Geekhonize.App.App>
{
    protected override AppBuilder CustomizeAppBuilder(AppBuilder builder)
        => base.CustomizeAppBuilder(builder).WithInterFont();
}
