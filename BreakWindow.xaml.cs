using System.IO;
using System.Runtime.InteropServices;
using System.ComponentModel;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace EyeRest;

public partial class BreakWindow : Window
{
    private readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromSeconds(1) };
    private int secondsRemaining;
    private readonly int screenX;
    private readonly int screenY;
    private readonly int screenWidth;
    private readonly int screenHeight;
    private bool allowControllerClose;
    public event EventHandler? RequestEnd;
    public event EventHandler? RequestEarlyEnd;

    public BreakWindow(int seconds, string? imagePath, bool isPrimary, int earlyEndRemaining, int x, int y, int width, int height)
    {
        InitializeComponent();
        secondsRemaining = seconds;
        screenX = x;
        screenY = y;
        screenWidth = width;
        screenHeight = height;
        BreakCountdownText.Text = secondsRemaining.ToString();
        EndButton.Visibility = isPrimary ? Visibility.Visible : Visibility.Collapsed;
        EndButton.IsEnabled = earlyEndRemaining > 0;
        EndButton.Opacity = earlyEndRemaining > 0 ? 1 : 0.55;
        EndButton.Content = earlyEndRemaining > 0
            ? $"提前结束休息（本月剩余 {earlyEndRemaining} 次）"
            : "本月提前结束机会已用完";

        if (!string.IsNullOrWhiteSpace(imagePath) && File.Exists(imagePath))
        {
            var bitmap = new BitmapImage();
            bitmap.BeginInit();
            bitmap.CacheOption = BitmapCacheOption.OnLoad;
            bitmap.UriSource = new Uri(imagePath, UriKind.Absolute);
            bitmap.EndInit();
            Backdrop.Background = new ImageBrush(bitmap) { Stretch = Stretch.UniformToFill };
        }

        timer.Tick += Timer_Tick;
        Loaded += (_, _) =>
        {
            var handle = new WindowInteropHelper(this).Handle;
            SetWindowPos(handle, new IntPtr(-1), screenX, screenY, screenWidth, screenHeight, 0x0040);
            timer.Start();
        };
        Closed += (_, _) => timer.Stop();
    }

    private void Timer_Tick(object? sender, EventArgs e)
    {
        secondsRemaining--;
        BreakCountdownText.Text = Math.Max(0, secondsRemaining).ToString();
        if (secondsRemaining <= 0)
        {
            timer.Stop();
            RequestEnd?.Invoke(this, EventArgs.Empty);
        }
    }

    private void EndButton_Click(object sender, RoutedEventArgs e)
    {
        if (!EndButton.IsEnabled) return;
        timer.Stop();
        RequestEarlyEnd?.Invoke(this, EventArgs.Empty);
    }

    public void CloseFromController()
    {
        allowControllerClose = true;
        Close();
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        if (!allowControllerClose) e.Cancel = true;
        base.OnClosing(e);
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SetWindowPos(IntPtr hWnd, IntPtr hWndInsertAfter,
        int x, int y, int width, int height, uint flags);
}
