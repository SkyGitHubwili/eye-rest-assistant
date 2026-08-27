using System.IO;
using System.Windows;

namespace EyeRest;

public partial class App : System.Windows.Application
{
    public App()
    {
        DispatcherUnhandledException += (_, e) =>
        {
            try
            {
                Directory.CreateDirectory(SettingsService.DataDirectory);
                File.WriteAllText(Path.Combine(SettingsService.DataDirectory, "error.log"), e.Exception.ToString());
            }
            catch { }
            System.Windows.MessageBox.Show($"程序遇到问题：{e.Exception.Message}", "护眼助手", MessageBoxButton.OK, MessageBoxImage.Error);
            e.Handled = true;
        };
    }
}
