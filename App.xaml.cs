using System.IO;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;

namespace EyeRest;

public partial class App : System.Windows.Application
{
    static App()
    {
        // 必须在创建任何窗口句柄之前关闭硬件加速。
        RenderOptions.ProcessRenderMode = RenderMode.SoftwareOnly;
    }

    public App()
    {
        // 双显卡 + 向日葵/Todesk 等虚拟显示器时，WPF 硬件加速常误报
        // “Insufficient memory to continue the execution of the program.”
        RenderOptions.ProcessRenderMode = RenderMode.SoftwareOnly;

        DispatcherUnhandledException += (_, e) =>
        {
            WriteErrorLog(e.Exception);
            System.Windows.MessageBox.Show(UserMessage(e.Exception), "护眼助手", MessageBoxButton.OK, MessageBoxImage.Error);
            e.Handled = true;
            if (e.Exception is OutOfMemoryException)
                Shutdown();
        };
        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
        {
            if (e.ExceptionObject is Exception ex)
            {
                WriteErrorLog(ex);
                try
                {
                    System.Windows.MessageBox.Show(UserMessage(ex), "护眼助手", MessageBoxButton.OK, MessageBoxImage.Error);
                }
                catch { }
            }
        };
    }

    protected override void OnStartup(StartupEventArgs e)
    {
        RenderOptions.ProcessRenderMode = RenderMode.SoftwareOnly;
        base.OnStartup(e);
    }

    protected override void OnSessionEnding(SessionEndingCancelEventArgs e)
    {
        if (MainWindow is EyeRest.MainWindow window) window.PrepareForSystemShutdown();
        base.OnSessionEnding(e);
    }

    private static void WriteErrorLog(Exception exception)
    {
        try
        {
            Directory.CreateDirectory(SettingsService.DataDirectory);
            File.WriteAllText(Path.Combine(SettingsService.DataDirectory, "error.log"), exception.ToString());
        }
        catch { }
    }

    private static string UserMessage(Exception exception)
    {
        if (exception is OutOfMemoryException)
            return "显示组件初始化失败（常见于双显卡或远程/虚拟显示器）。已改为软件渲染，请关闭本窗口后重新打开护眼助手。";
        return $"程序遇到问题：{exception.Message}";
    }
}
