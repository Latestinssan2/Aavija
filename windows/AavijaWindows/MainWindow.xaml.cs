using System;
using System.IO;
using System.Linq;
using System.Windows;
using Microsoft.Win32;
using Aavija.Core;

namespace Aavija;

public partial class MainWindow : Window
{
    private readonly PairingStore _store = new();
    private readonly PhoneClient _client;
    private readonly GestureController _gesture = new();
    private readonly ListenerStore _listenerStore = new();
    private DesktopListener? _listener;
    private readonly PhoneDiscovery _discovery = new();

    public MainWindow()
    {
        InitializeComponent();
        _client = new PhoneClient(_store, () => Environment.MachineName);
        _client.StatusChanged += msg => Dispatcher.Invoke(() => StatusText.Text = msg);
        _gesture.GestureDetected += g => Dispatcher.Invoke(() =>
        {
            GestureStatusText.Text = g;
            _client.OnLocalGesture(g);
        });
        Loaded += async (_, _) =>
        {
            _gesture.Start();
            await RefreshDiscoveryAsync();
            DownloadFolderBox.Text = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "Aavija");
            ScreenshotFolderBox.Text = Path.Combine(Path.GetTempPath(), "AavijaScreenshots");
        };
        Closed += (_, _) => _gesture.Stop();
    }

    private async System.Threading.Tasks.Task RefreshDiscoveryAsync()
    {
        try
        {
            await _discovery.StartAsync();
            PhoneCombo.Items.Clear();
            foreach (var p in _discovery.Peers)
                PhoneCombo.Items.Add($"{p.Name} ({p.Host}:{p.Port})");
            if (PhoneCombo.Items.Count > 0) PhoneCombo.SelectedIndex = 0;
        }
        catch { }
    }

    private void RefreshDiscovery_Click(object sender, RoutedEventArgs e) => _ = RefreshDiscoveryAsync();

    private PhonePeer CurrentPeer()
    {
        int port = int.TryParse(PortBox.Text, out var p) ? p : 0;
        return new PhonePeer(HostBox.Text, HostBox.Text, HostBox.Text, port);
    }

    private async void Pair_Click(object sender, RoutedEventArgs e)
    {
        var peer = CurrentPeer();
        var (ok, token) = await _client.PairAsync(peer, PinBox.Text, RequestIdBox.Text);
        StatusText.Text = ok ? $"Paired. Token: {token?[..8]}..." : "Pairing failed.";
        if (ok && token is not null) _client.StartHeartbeat(peer, token);
    }

    private async void Push_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new Microsoft.Win32.OpenFileDialog();
        if (dlg.ShowDialog() != true) return;
        StatusText.Text = "Pushing...";
        await _client.PushFileAsync(CurrentPeer(), dlg.FileName);
    }

    private async void Pull_Click(object sender, RoutedEventArgs e)
    {
        var saveDir = DownloadFolderBox.Text;
        StatusText.Text = "Pulling...";
        var path = await _client.PullFileAsync(CurrentPeer(), saveDir);
        StatusText.Text = path is null ? "Pull failed." : $"Saved: {path}";
    }

    private void CaptureScreen_Click(object sender, RoutedEventArgs e)
    {
        try { StatusText.Text = $"Saved screenshot: {ScreenshotCapture.CaptureScreen()}"; }
        catch (Exception ex) { StatusText.Text = $"Screenshot failed: {ex.Message}"; }
    }

    private void CaptureActiveWindow_Click(object sender, RoutedEventArgs e)
    {
        try { StatusText.Text = $"Saved screenshot: {ScreenshotCapture.CaptureActiveWindow()}"; }
        catch (Exception ex) { StatusText.Text = $"Screenshot failed: {ex.Message}"; }
    }

    private void ChooseDownloadFolder_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new System.Windows.Forms.FolderBrowserDialog();
        if (dlg.ShowDialog() == System.Windows.Forms.DialogResult.OK)
            DownloadFolderBox.Text = dlg.SelectedPath;
    }

    private void ChooseScreenshotFolder_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new System.Windows.Forms.FolderBrowserDialog();
        if (dlg.ShowDialog() == System.Windows.Forms.DialogResult.OK)
            ScreenshotFolderBox.Text = dlg.SelectedPath;
    }

    private void StartGesture_Click(object sender, RoutedEventArgs e)
    {
        _gesture.Start();
        GestureStatusText.Text = "Gesture mode started.";
    }

    private void StopGesture_Click(object sender, RoutedEventArgs e)
    {
        _gesture.Stop();
        GestureStatusText.Text = "Gesture mode stopped.";
    }

    private void StartListener_Click(object sender, RoutedEventArgs e)
    {
        _listenerStore.Pin = ListenerPinBox.Text;
        _listener ??= new DesktopListener(_listenerStore);
        _listener.StatusChanged += msg => Dispatcher.Invoke(() => StatusText.Text = msg);
        _listener.Start(0);
        ListenerStatusText.Text = $"Listener running on port {_listener.Port}";
    }

    private void StopListener_Click(object sender, RoutedEventArgs e)
    {
        _listener?.Stop();
        ListenerStatusText.Text = "Stopped";
    }
}
