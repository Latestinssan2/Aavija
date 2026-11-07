using System;
using System.Diagnostics;
using System.IO;
using System.Threading.Tasks;

namespace Aavija.Core;

public class GestureController
{
    public event Action<string>? GestureDetected;

    private Process? _process;

    public void Start()
    {
        Stop();
        var script = Path.Combine(AppContext.BaseDirectory, "gesture_sidecar.py");
        if (!File.Exists(script))
        {
            // Fall back to repo-relative path during development.
            script = Path.GetFullPath(Path.Combine("..", "..", "..", "..", "windows", "mediapipe_sidecar", "gesture_sidecar.py"));
        }
        if (!File.Exists(script)) return;

        _process = new Process
        {
            StartInfo = new ProcessStartInfo
            {
                FileName = "python",
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
        _process.Start();
        _process.BeginOutputReadLine();
    }

    public void Stop()
    {
        try { _process?.Kill(); } catch { }
        _process = null;
    }
}
