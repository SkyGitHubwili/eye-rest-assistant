using System.IO;
using System.Text.Json;

namespace EyeRest;

public static class SettingsService
{
    public static string DataDirectory { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "护眼助手");

    private static readonly string[] LegacyDirectories =
    [
        @"F:\护眼助手",
        @"D:\Software\护眼助手",
    ];

    private static string SettingsPath => Path.Combine(DataDirectory, "settings.json");

    public static EyeRestSettings Load()
    {
        try
        {
            Directory.CreateDirectory(DataDirectory);
            foreach (var path in CandidateSettingsPaths())
            {
                if (!File.Exists(path)) continue;
                var loaded = JsonSerializer.Deserialize<EyeRestSettings>(File.ReadAllText(path));
                if (loaded is null) continue;
                if (!string.Equals(path, SettingsPath, StringComparison.OrdinalIgnoreCase))
                {
                    try { Save(loaded); } catch { }
                }
                return loaded;
            }
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

    private static IEnumerable<string> CandidateSettingsPaths()
    {
        yield return SettingsPath;
        foreach (var directory in LegacyDirectories)
            yield return Path.Combine(directory, "settings.json");
    }
}
