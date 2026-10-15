using System.Windows.Interop;
using System.Windows.Media;

namespace EyeRest;

internal static class Program
{
    [STAThread]
    public static void Main()
    {
        RenderOptions.ProcessRenderMode = RenderMode.SoftwareOnly;
        var app = new App();
        app.InitializeComponent();
        app.Run();
    }
}
