using System.IO;
using System.Text.Json;

namespace EyeRest;

public static class SettingsService
{
    public static string DataDirectory { get; } = @"F:\护眼助手";
    private static string SettingsPath => Path.Combine(DataDirectory, "settings.json");

    public static EyeRestSettings Load()
    {
        try
        {
            Directory.CreateDirectory(DataDirectory);
            if (File.Exists(SettingsPath))
                return JsonSerializer.Deserialize<EyeRestSettings>(File.ReadAllText(SettingsPath)) ?? new();
        }
        catch
        {
            // 使用默认设置，避免磁盘异常阻止应用启动。
        }
        return new();
    }

    public static void Save(EyeRestSettings settings)
    {
        Directory.CreateDirectory(DataDirectory);
        File.WriteAllText(SettingsPath, JsonSerializer.Serialize(settings, new JsonSerializerOptions { WriteIndented = true }));
    }
}
