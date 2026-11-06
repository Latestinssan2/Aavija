using System.Windows;

namespace Aavija;

public partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
    }

    private void CaptureScreen_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            string path = ScreenshotCapture.CaptureScreen();
            StatusText.Text = $"Saved screenshot: {path}";
        }
        catch (Exception ex)
        {
            StatusText.Text = $"Screenshot failed: {ex.Message}";
        }
    }

    private void CaptureActiveWindow_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            string path = ScreenshotCapture.CaptureActiveWindow();
            StatusText.Text = $"Saved screenshot: {path}";
        }
        catch (Exception ex)
        {
            StatusText.Text = $"Screenshot failed: {ex.Message}";
        }
    }
}
