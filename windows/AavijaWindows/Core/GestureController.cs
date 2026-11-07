using System;
using System.Diagnostics;
using System.IO;
using System.Threading.Tasks;

namespace Aavija.Core;

public class GestureController
{
    public event Action<string>? GestureDetected;
    public string Status { get; private set; } = "Not started";

    private Process? _process;

    public void Start()
    {
        Stop();
        var script = Path.Combine(AppContext.BaseDirectory, "gesture_sidecar.py");
        if (!File.Exists(script))
        {
            script = Path.GetFullPath(Path.Combine("..", "..", "..", "..", "windows", "mediapipe_sidecar", "gesture_sidecar.py"));
        }
        if (!File.Exists(script)) { Status = "Sidecar script not found."; return; }

        string python = "python";
        try
        {
            var check = Process.Start(new ProcessStartInfo { FileName = "python", Arguments = "--version", RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false, CreateNoWindow = true });
            check?.WaitForExit(2000);
            if (check is null || check.ExitCode != 0) python = "python3";
        }
        catch { python = "python3"; }

        _process = new Process
        {
            StartInfo = new ProcessStartInfo
            {
                FileName = python,
                Arguments = $"\"{script}\"",
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
            }
        };
        _process.OutputDataReceived += (_, e) =>
        {
            if (string.IsNullOrWhiteSpace(e.Data)) return;
            var line = e.Data.Trim();
            if (line == "GRAB" || line == "RELEASE")
                GestureDetected?.Invoke(line);
        };
        _process.ErrorDataReceived += (_, e) =>
        {
            if (!string.IsNullOrWhiteSpace(e.Data))
                Status = e.Data.Trim();
        };
        _process.Start();
        _process.BeginOutputReadLine();
        _process.BeginErrorReadLine();
        Status = "Gesture sidecar started.";
    }

    public void Stop()
    {
        try { _process?.Kill(); } catch { }
        _process = null;
    }
}
