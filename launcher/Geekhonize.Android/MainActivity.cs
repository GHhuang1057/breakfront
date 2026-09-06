using Android.App;
using Android.Content.PM;
using Avalonia;
using Avalonia.Android;

namespace Geekhonize.Android;

[Activity(
    Label = "BREAKFRONT 启动器",
    MainLauncher = true,
    LaunchMode = LaunchMode.SingleTop,
    ConfigurationChanges = ConfigChanges.Orientation | ConfigChanges.ScreenSize
        | ConfigChanges.ScreenLayout | ConfigChanges.KeyboardHidden | ConfigChanges.UiMode)]
public class MainActivity : AvaloniaMainActivity<Geekhonize.App.App>
{
    protected override AppBuilder CustomizeAppBuilder(AppBuilder builder)
        => base.CustomizeAppBuilder(builder).WithInterFont();
}
