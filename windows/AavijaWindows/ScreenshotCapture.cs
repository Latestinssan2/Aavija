using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;

namespace Aavija;

public static class ScreenshotCapture
{
    private const int SM_CXSCREEN = 0;
    private const int SM_CYSCREEN = 1;
    private const uint PW_RENDERFULLCONTENT = 0x00000002;

    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll")]
    private static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);

    [DllImport("user32.dll")]
    private static extern bool PrintWindow(IntPtr hwnd, IntPtr hdcBlt, uint nFlags);

    [DllImport("user32.dll")]
    private static extern int GetSystemMetrics(int nIndex);

    [StructLayout(LayoutKind.Sequential)]
    private struct RECT
    {
        public int Left, Top, Right, Bottom;
    }

    public static string CaptureScreen()
    {
        int width = GetSystemMetrics(SM_CXSCREEN);
        int height = GetSystemMetrics(SM_CYSCREEN);
        using var bitmap = new Bitmap(width, height, PixelFormat.Format32bppArgb);
        using var graphics = Graphics.FromImage(bitmap);
        graphics.CopyFromScreen(0, 0, 0, 0, new Size(width, height), CopyPixelOperation.SourceCopy);
        return Save(bitmap, "Aavija-Screen");
    }

    public static string CaptureActiveWindow()
    {
        IntPtr hwnd = GetForegroundWindow();
        if (hwnd == IntPtr.Zero)
        {
            throw new InvalidOperationException("No foreground window is available.");
        }
        if (!GetWindowRect(hwnd, out RECT rect))
        {
            throw new InvalidOperationException("Could not read the foreground window bounds.");
        }
        int width = Math.Max(1, rect.Right - rect.Left);
        int height = Math.Max(1, rect.Bottom - rect.Top);

        using var bitmap = new Bitmap(width, height, PixelFormat.Format32bppArgb);
        using var graphics = Graphics.FromImage(bitmap);
        IntPtr hdc = graphics.GetHdc();
        try
        {
            if (!PrintWindow(hwnd, hdc, PW_RENDERFULLCONTENT))
            {
                // Fall back to a plain screen copy of the window rectangle.
                graphics.ReleaseHdc(hdc);
                hdc = IntPtr.Zero;
                graphics.CopyFromScreen(rect.Left, rect.Top, 0, 0, new Size(width, height), CopyPixelOperation.SourceCopy);
            }
        }
        finally
        {
            if (hdc != IntPtr.Zero)
            {
                graphics.ReleaseHdc(hdc);
            }
        }
        return Save(bitmap, "Aavija-ActiveWindow");
    }

    private static string Save(Bitmap bitmap, string prefix)
    {
        string dir = Path.Combine(Path.GetTempPath(), "AavijaScreenshots");
        Directory.CreateDirectory(dir);
        string name = $"{prefix}-{DateTime.Now:yyyy-MM-dd-HHmmss-fff}.png";
        string path = Path.Combine(dir, name);
        bitmap.Save(path, ImageFormat.Png);
        return path;
    }
}
