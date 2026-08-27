using Microsoft.Win32;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using WinForms = System.Windows.Forms;
using Color = System.Windows.Media.Color;
using Point = System.Windows.Point;
using OpenFileDialog = Microsoft.Win32.OpenFileDialog;
using MessageBox = System.Windows.MessageBox;

namespace EyeRest;

public partial class MainWindow : Window
{
    private readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromSeconds(1) };
    private readonly DispatcherTimer scheduleTimer = new() { Interval = TimeSpan.FromSeconds(30) };
    private EyeRestSettings settings;
    private TimeSpan remaining;
    private bool running;
    private bool loading = true;
    private bool schedulePaused;
    private readonly List<BreakWindow> breakWindows = [];

    public MainWindow()
    {
        InitializeComponent();
        settings = SettingsService.Load();
        for (var hour = 0; hour < 24; hour++)
        {
            ActiveStartHourBox.Items.Add(new ComboBoxItem { Content = $"{hour:00}:00", Tag = hour });
            ActiveEndHourBox.Items.Add(new ComboBoxItem { Content = $"{hour:00}:00", Tag = hour });
        }
        SelectByTag(WorkMinutesBox, settings.WorkMinutes);
        SelectByTag(BreakSecondsBox, settings.BreakSeconds);
        SelectByTag(ActiveStartHourBox, settings.ActiveStartHour);
        SelectByTag(ActiveEndHourBox, settings.ActiveEndHour);
        StartWithWindowsCheck.IsChecked = settings.StartWithWindows;
        ScheduleEnabledCheck.IsChecked = settings.ScheduleEnabled;
        SchedulePanel.IsEnabled = settings.ScheduleEnabled;
        ApplyPreview();
        ResetCountdown();
        timer.Tick += Timer_Tick;
        scheduleTimer.Tick += (_, _) => ApplyScheduleState();
        scheduleTimer.Start();
        loading = false;
        try { StartupService.SetEnabled(settings.StartWithWindows); } catch { }
        if (settings.StartAutomatically && IsWithinActiveHours()) StartTimer();
        else if (!IsWithinActiveHours()) { schedulePaused = settings.StartAutomatically; EnterSleepState(); }
        else PauseTimer();
        if (Environment.GetCommandLineArgs().Any(x => x.Equals("--autostart", StringComparison.OrdinalIgnoreCase)))
            WindowState = WindowState.Minimized;
    }

    private static void SelectByTag(System.Windows.Controls.ComboBox box, int value)
    {
        foreach (ComboBoxItem item in box.Items)
            if (item.Tag?.ToString() == value.ToString()) { box.SelectedItem = item; return; }
        box.SelectedIndex = 0;
    }

    private static int SelectedValue(System.Windows.Controls.ComboBox box) =>
        int.Parse(((ComboBoxItem)box.SelectedItem).Tag.ToString()!);

    private void Timer_Tick(object? sender, EventArgs e)
    {
        if (remaining.TotalSeconds <= 1)
        {
            timer.Stop();
            remaining = TimeSpan.Zero;
            UpdateCountdown();
            ShowBreak();
            return;
        }
        remaining -= TimeSpan.FromSeconds(1);
        UpdateCountdown();
    }

    private void StartTimer()
    {
        if (!IsWithinActiveHours()) { EnterSleepState(); return; }
        running = true;
        timer.Start();
        ToggleButton.Content = "暂停计时";
        StatusText.Text = "专注中";
        StatusText.Foreground = new SolidColorBrush(Color.FromRgb(45, 122, 89));
        StatusDot.Fill = StatusText.Foreground;
    }

    private void PauseTimer()
    {
        running = false;
        timer.Stop();
        ToggleButton.Content = "继续计时";
        StatusText.Text = "已暂停";
        StatusText.Foreground = new SolidColorBrush(Color.FromRgb(126, 134, 129));
        StatusDot.Fill = StatusText.Foreground;
    }

    private void EnterSleepState()
    {
        if (running) schedulePaused = true;
        running = false;
        timer.Stop();
        ToggleButton.IsEnabled = false;
        ToggleButton.Content = "睡眠时段";
        StatusText.Text = "睡眠时段";
        StatusText.Foreground = new SolidColorBrush(Color.FromRgb(126, 134, 129));
        StatusDot.Fill = StatusText.Foreground;
    }

    private bool IsWithinActiveHours()
    {
        if (!settings.ScheduleEnabled) return true;
        var hour = DateTime.Now.Hour;
        var start = settings.ActiveStartHour;
        var end = settings.ActiveEndHour;
        if (start == end) return true;
        return start < end ? hour >= start && hour < end : hour >= start || hour < end;
    }

    private void ApplyScheduleState()
    {
        SchedulePanel.IsEnabled = settings.ScheduleEnabled;
        if (!IsWithinActiveHours())
        {
            EnterSleepState();
            return;
        }
        ToggleButton.IsEnabled = true;
        if (schedulePaused)
        {
            schedulePaused = false;
            ResetCountdown();
            StartTimer();
        }
    }

    private void ResetCountdown()
    {
        remaining = TimeSpan.FromMinutes(settings.WorkMinutes);
        UpdateCountdown();
        CycleText.Text = $"每 {settings.WorkMinutes} 分钟休息 {FormatBreakDuration(settings.BreakSeconds)}";
    }

    private void UpdateCountdown() => CountdownText.Text = $"{(int)remaining.TotalMinutes:00}:{remaining.Seconds:00}";

    private static string FormatBreakDuration(int seconds) => seconds < 60 ? $"{seconds} 秒" : $"{seconds / 60} 分钟";

    private void ShowBreak()
    {
        if (breakWindows.Count > 0) return;
        NormalizeEarlyEndUsage();
        var earlyEndRemaining = Math.Max(0, 3 - settings.EarlyEndCount);
        Hide();
        foreach (var screen in WinForms.Screen.AllScreens)
        {
            var window = new BreakWindow(
                settings.BreakSeconds,
                settings.ImagePath,
                screen.Primary,
                earlyEndRemaining,
                screen.Bounds.X,
                screen.Bounds.Y,
                screen.Bounds.Width,
                screen.Bounds.Height);
            window.RequestEnd += EndBreak;
            window.RequestEarlyEnd += EndBreakEarly;
            breakWindows.Add(window);
            window.Show();
        }
    }

    private void EndBreak(object? sender, EventArgs e)
    {
        foreach (var window in breakWindows.ToArray())
        {
            window.RequestEnd -= EndBreak;
            window.RequestEarlyEnd -= EndBreakEarly;
            window.CloseFromController();
        }
        breakWindows.Clear();
        Show();
        Activate();
        ResetCountdown();
        if (IsWithinActiveHours()) StartTimer(); else EnterSleepState();
    }

    private void EndBreakEarly(object? sender, EventArgs e)
    {
        NormalizeEarlyEndUsage();
        if (settings.EarlyEndCount >= 3) return;
        settings.EarlyEndCount++;
        SettingsService.Save(settings);
        EndBreak(sender, EventArgs.Empty);
    }

    private void NormalizeEarlyEndUsage()
    {
        var month = DateTime.Now.ToString("yyyy-MM");
        if (settings.EarlyEndMonth == month) return;
        settings.EarlyEndMonth = month;
        settings.EarlyEndCount = 0;
        SettingsService.Save(settings);
    }

    private void ToggleButton_Click(object sender, RoutedEventArgs e)
    {
        if (running) PauseTimer(); else StartTimer();
    }

    private void ResetButton_Click(object sender, RoutedEventArgs e) => ResetCountdown();
    private void BreakNowButton_Click(object sender, RoutedEventArgs e) { timer.Stop(); ShowBreak(); }

    private void Setting_Changed(object sender, SelectionChangedEventArgs e)
    {
        if (loading || WorkMinutesBox.SelectedItem is null || BreakSecondsBox.SelectedItem is null) return;
        settings.WorkMinutes = SelectedValue(WorkMinutesBox);
        settings.BreakSeconds = SelectedValue(BreakSecondsBox);
        SettingsService.Save(settings);
        ResetCountdown();
    }

    private void Automation_Changed(object sender, RoutedEventArgs e)
    {
        if (loading || ActiveStartHourBox.SelectedItem is null || ActiveEndHourBox.SelectedItem is null) return;
        settings.StartWithWindows = StartWithWindowsCheck.IsChecked == true;
        settings.ScheduleEnabled = ScheduleEnabledCheck.IsChecked == true;
        settings.ActiveStartHour = SelectedValue(ActiveStartHourBox);
        settings.ActiveEndHour = SelectedValue(ActiveEndHourBox);
        SettingsService.Save(settings);
        SchedulePanel.IsEnabled = settings.ScheduleEnabled;
        try
        {
            StartupService.SetEnabled(settings.StartWithWindows);
        }
        catch (Exception ex)
        {
            MessageBox.Show($"开机自启设置失败：{ex.Message}", "护眼助手", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
        ApplyScheduleState();
    }

    private void Automation_Changed(object sender, SelectionChangedEventArgs e) => Automation_Changed(sender, new RoutedEventArgs());

    private void ChooseImageButton_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog
        {
            Title = "选择休息画面",
            Filter = "图片文件|*.jpg;*.jpeg;*.png;*.bmp;*.gif|所有文件|*.*"
        };
        if (dialog.ShowDialog() != true) return;

        try
        {
            Directory.CreateDirectory(SettingsService.DataDirectory);
            var extension = Path.GetExtension(dialog.FileName);
            var destination = Path.Combine(SettingsService.DataDirectory, "休息画面" + extension);
            File.Copy(dialog.FileName, destination, true);
            settings.ImagePath = destination;
            SettingsService.Save(settings);
            ApplyPreview();
        }
        catch (Exception ex)
        {
            MessageBox.Show($"图片设置失败：{ex.Message}", "护眼助手", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private void DefaultImageButton_Click(object sender, RoutedEventArgs e)
    {
        settings.ImagePath = null;
        SettingsService.Save(settings);
        ApplyPreview();
    }

    private void ApplyPreview()
    {
        if (!string.IsNullOrWhiteSpace(settings.ImagePath) && File.Exists(settings.ImagePath))
        {
            var bitmap = new BitmapImage();
            bitmap.BeginInit();
            bitmap.CacheOption = BitmapCacheOption.OnLoad;
            bitmap.UriSource = new Uri(settings.ImagePath, UriKind.Absolute);
            bitmap.EndInit();
            PreviewBackground.Background = new ImageBrush(bitmap) { Stretch = Stretch.UniformToFill };
            PreviewHint.Visibility = Visibility.Collapsed;
            ImageStatusText.Text = Path.GetFileName(settings.ImagePath);
        }
        else
        {
            PreviewBackground.Background = new LinearGradientBrush(
                new GradientStopCollection { new(Color.FromRgb(23, 60, 53), 0), new(Color.FromRgb(91, 155, 115), .55), new(Color.FromRgb(210, 182, 124), 1) },
                new Point(0, 0), new Point(1, 1));
            PreviewHint.Visibility = Visibility.Visible;
            ImageStatusText.Text = "自然渐变背景";
        }
    }
}
