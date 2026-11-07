using System;
using System.IO;
using System.Windows;
using Aavija.Core;
using Microsoft.Win32;

namespace Aavija;

public partial class MainWindow : Window
{
    private readonly PairingStore _store = new();
    private readonly PhoneClient _client;
    private readonly GestureController _gesture = new();
    private readonly ListenerStore _listenerStore = new();
    private DesktopListener? _listener;

    public MainWindow()
    {
        InitializeComponent();
        _client = new PhoneClient(_store, () => Environment.MachineName);
        _client.StatusChanged += msg => Dispatcher.Invoke(() => StatusText.Text = msg);
        _gesture.GestureDetected += g => Dispatcher.Invoke(() =>
        {
            StatusText.Text = $"Gesture: {g}";
            _client.OnLocalGesture(g);
        });
        Loaded += (_, _) => _gesture.Start();
        Closed += (_, _) => _gesture.Stop();
    }

    private PhonePeer CurrentPeer()
    {
        int port = int.TryParse(PortBox.Text, out var p) ? p : 0;
        return new PhonePeer(HostBox.Text, HostBox.Text, HostBox.Text, port);
    }

    private async void Pair_Click(object sender, RoutedEventArgs e)
    {
        var peer = CurrentPeer();
        var pin = PinBox.Text;
        var req = RequestIdBox.Text;
        StatusText.Text = "Pairing...";
        var (ok, token) = await _client.PairAsync(peer, pin, req);
        StatusText.Text = ok ? $"Paired. Token: {token?[..8]}..." : "Pairing failed.";
        if (ok && token is not null) _client.StartHeartbeat(peer, token);
    }

    private async void Push_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFileDialog();
        if (dlg.ShowDialog() != true) return;
        StatusText.Text = "Pushing...";
        await _client.PushFileAsync(CurrentPeer(), dlg.FileName);
    }

    private async void Pull_Click(object sender, RoutedEventArgs e)
    {
        var saveDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "Aavija");
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

    private void StartListener_Click(object sender, RoutedEventArgs e)
    {
        _listenerStore.Pin = ListenerPinBox.Text;
        _listener ??= new DesktopListener(_listenerStore);
        _listener.StatusChanged += msg => Dispatcher.Invoke(() => StatusText.Text = msg);
        _listener.Start(0);
        StatusText.Text = $"Listener started on port {_listener.Port}";
    }

    private void StopListener_Click(object sender, RoutedEventArgs e)
    {
        _listener?.Stop();
        StatusText.Text = "Listener stopped.";
    }
}
