using System.IO;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace EyeRest;

internal static class ImageLoader
{
    public static ImageSource? TryLoad(string? path, int maxPixelWidth)
    {
        if (string.IsNullOrWhiteSpace(path) || !File.Exists(path)) return null;

        try
        {
            var bitmap = new BitmapImage();
            bitmap.BeginInit();
            bitmap.CacheOption = BitmapCacheOption.OnLoad;
            bitmap.CreateOptions = BitmapCreateOptions.IgnoreColorProfile | BitmapCreateOptions.IgnoreImageCache;
            bitmap.DecodePixelWidth = Math.Clamp(maxPixelWidth, 320, 2560);
            bitmap.UriSource = new Uri(path, UriKind.Absolute);
            bitmap.EndInit();
            bitmap.Freeze();
            return bitmap;
        }
        catch (Exception ex) when (ex is OutOfMemoryException or IOException or NotSupportedException or ArgumentException)
        {
            return null;
        }
    }
}
