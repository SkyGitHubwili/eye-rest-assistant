namespace EyeRest;

public sealed class EyeRestSettings
{
    public int WorkMinutes { get; set; } = 20;
    public int BreakSeconds { get; set; } = 20;
    public string? ImagePath { get; set; }
    public bool StartAutomatically { get; set; } = true;
    public bool StartWithWindows { get; set; }
    public bool ScheduleEnabled { get; set; } = true;
    public int ActiveStartHour { get; set; } = 8;
    public int ActiveEndHour { get; set; } = 23;
    public string EarlyEndMonth { get; set; } = "";
    public int EarlyEndCount { get; set; }
}
